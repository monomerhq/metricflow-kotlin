package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.metric_evaluation.MetricEvaluationPlanner
import cc.monomer.metricflow.domain.metric_evaluation.plan.DerivedMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryDependencyEdge
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElement
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElementCollector
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElementLookup
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryPropertySet
import cc.monomer.metricflow.domain.metric_evaluation.plan.MutableMetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.TopLevelQueryNode
import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Builds metric evaluation plans that let derived-metric query nodes pass
 * through input metrics so the engine can satisfy multiple top-level metrics
 * with fewer joins.
 *
 * Port of
 * `metricflow.metric_evaluation.passthrough.passthrough_me_planner.PassThroughMetricEvaluationPlanner`.
 *
 * Algorithm:
 *
 * 1. Walk the metric dependency graph from the top-level metrics, collecting
 *    every `MetricQueryElement` that needs to be evaluated.
 * 2. Group elements by evaluation level (level 0 = base metrics, level N =
 *    derived metrics whose inputs are at level <N).
 * 3. For each level, build derived-metric nodes that may pass through the
 *    inputs they consume — see the worked example in the class KDoc of the
 *    Python equivalent.
 * 4. Consolidate sibling derived nodes with identical sources into a single
 *    node.
 * 5. Build the top-level query node, then prune passthrough specs that are
 *    not transitively used by any parent query node.
 */
class PassThroughMetricEvaluationPlanner(
    manifestObjectLookup: ManifestObjectLookup,
    metricLookup: MetricLookup,
    columnAssociationResolver: ColumnAssociationResolver,
) : MetricEvaluationPlanner(manifestObjectLookup, metricLookup, columnAssociationResolver) {

    private val levelResolver = MetricEvaluationLevelResolver(manifestObjectLookup)

    override fun buildPlan(
        metricSpecs: List<MetricSpec>,
        groupByItemSpecs: List<LinkableInstanceSpec>,
        predicatePushdownState: PredicatePushdownState,
        filterSpecFactory: WhereFilterSpecFactory,
    ): MetricEvaluationPlan {
        val queryElementCollector = MetricQueryElementCollector()

        val topLevelQueryElements: OrderedSet<MetricQueryElement> = FrozenOrderedSet(
            metricSpecs.map { metricSpec ->
                MetricQueryElement.create(
                    metricSpec = metricSpec,
                    groupByItemSpecs = groupByItemSpecs,
                    predicatePushdownState = predicatePushdownState,
                )
            },
        )

        for (queryElement in topLevelQueryElements) {
            recursivelyCollectQueryElements(
                queryElement = queryElement,
                queryElementCollector = queryElementCollector,
                filterSpecFactory = filterSpecFactory,
            )
        }

        val queryElementLookup: MetricQueryElementLookup = queryElementCollector

        // Group query elements by level.
        val levelToQueryElements = groupQueryElementsByLevel(queryElementLookup)
        val queryElementToLevel = LinkedHashMap<MetricQueryElement, Int>()
        for ((level, elements) in levelToQueryElements) {
            for (element in elements) queryElementToLevel[element] = level
        }

        val evaluationPlan = MutableMetricEvaluationPlan.create()

        // Level 0 — base metrics.
        val baseQueryElements = levelToQueryElements[0]
            ?: throw MetricFlowInternalError("No level-0 query elements found.")
        val baseBuilder = BaseMetricQueryNodeBuilder(manifestObjectLookup)
        val baseNodes = baseBuilder.buildNodes(baseQueryElements)
        evaluationPlan.addNodes(baseNodes)

        val candidateNodes: MutableList<MetricQueryNode> = baseNodes.toMutableList()
        val maxLevel = levelToQueryElements.keys.max()

        // Level >0 — derived metric nodes.
        for (level in 1..maxLevel) {
            val currentElements = levelToQueryElements[level]
                ?: throw MetricFlowInternalError("Missing query elements at level $level.")
            val (outputNodes, edges) = generateSubplanForRecursiveMetricsAtSingleLevel(
                outputQueryElements = currentElements,
                candidateInputQueryNodes = candidateNodes,
                queryElementLookup = queryElementLookup,
                queryElementToLevel = queryElementToLevel,
            )

            val consolidator = DerivedMetricsNodeConsolidator(
                nodesToConsolidate = outputNodes,
                correspondingSourceEdges = edges,
            )
            val (consolidatedNodes, consolidatedEdges) = consolidator.consolidateNodes()
            candidateNodes.addAll(consolidatedNodes)
            evaluationPlan.addEdges(consolidatedEdges)
        }

        // Top-level query node.
        val topLevelSelector = BestMetricQuerySetSelector(queryElementToLevel)
        val topLevelResult = topLevelSelector.findBestQueries(
            desiredQueryElements = topLevelQueryElements,
            candidateInputNodes = candidateNodes,
        )

        if (topLevelResult.remainingDesiredQueryElements.isNotEmpty()) {
            throw MetricFlowInternalError(
                "Unable to get all top-level metrics from candidate queries. " +
                    "This indicates an error in candidate query generation. " +
                    "metricSpecs=$metricSpecs " +
                    "remaining=${topLevelResult.remainingDesiredQueryElements} " +
                    "candidates=$candidateNodes",
            )
        }

        val topLevelQueryNode = TopLevelQueryNode.create(
            passthroughMetricSpecs = metricSpecs,
            queryProperties = MetricQueryPropertySet.create(
                groupByItemSpecs = groupByItemSpecs,
                predicatePushdownState = predicatePushdownState,
            ),
        )

        for ((inputQueryNode, fulfilledElements) in topLevelResult.inputQueryNodeToFulfilledQueryElements) {
            for (queryElement in fulfilledElements) {
                evaluationPlan.addEdge(
                    MetricQueryDependencyEdge.create(
                        targetNode = topLevelQueryNode,
                        targetNodeOutputSpec = queryElement.metricSpec,
                        sourceNode = inputQueryNode,
                        sourceNodeOutputSpec = queryElement.metricSpec,
                    ),
                )
            }
        }

        evaluationPlan.validate()

        // Drop passthrough metric edges that nothing in the plan consumes.
        val nodeToRetainedSpecs = mapRetainedMetricSpecsByNode(
            queryGraph = evaluationPlan,
            rootQueryNode = topLevelQueryNode,
            metricSpecsToRetainForRootQueryNode = metricSpecs.toSet(),
        )

        val pruned = prunePlan(
            evaluationPlan = evaluationPlan,
            queryNode = topLevelQueryNode,
            nodeToMetricSpecsToRetain = nodeToRetainedSpecs,
        )
        return pruned
    }

    private fun prunePlan(
        evaluationPlan: MetricEvaluationPlan,
        queryNode: MetricQueryNode,
        nodeToMetricSpecsToRetain: Map<MetricQueryNode, OrderedSet<MetricSpec>>,
    ): MetricEvaluationPlan {
        val nodesToProcess: ArrayDeque<MetricQueryNode> = ArrayDeque<MetricQueryNode>().apply {
            addLast(queryNode)
        }
        val newEdges = mutableListOf<MetricQueryDependencyEdge>()
        val currentToNextNode = LinkedHashMap<MetricQueryNode, MetricQueryNode>()

        while (nodesToProcess.isNotEmpty()) {
            val currentNode = nodesToProcess.removeLast()
            if (currentNode in currentToNextNode) continue

            val retainedSpecs = nodeToMetricSpecsToRetain[currentNode]
                ?: throw MetricFlowInternalError(
                    "Missing retained metric specs for query node while pruning. " +
                        "currentNode=$currentNode knownNodes=${nodeToMetricSpecsToRetain.keys}",
                )

            val sourceEdges = evaluationPlan.sourceEdges(currentNode)
            val retainedSourceEdges = sourceEdges.filter { edge ->
                edge.targetNodeOutputSpec in retainedSpecs
            }

            val allSourcesProcessed = retainedSourceEdges.all { it.sourceNode in currentToNextNode }
            if (allSourcesProcessed) {
                val nextNode = currentNode.pruned(retainedSpecs.toSet())
                for (edge in retainedSourceEdges) {
                    val nextSourceNode = currentToNextNode[edge.headNode]
                        ?: throw MetricFlowInternalError(
                            "Source node was not registered: edge=$edge",
                        )
                    newEdges.add(
                        MetricQueryDependencyEdge.create(
                            targetNode = nextNode,
                            targetNodeOutputSpec = edge.targetNodeOutputSpec,
                            sourceNode = nextSourceNode,
                            sourceNodeOutputSpec = edge.sourceNodeOutputSpec,
                        ),
                    )
                }
                currentToNextNode[currentNode] = nextNode
                continue
            }

            nodesToProcess.addLast(currentNode)
            for (edge in retainedSourceEdges.reversed()) {
                nodesToProcess.addLast(edge.sourceNode)
            }
        }

        val newPlan = MutableMetricEvaluationPlan.create()
        newPlan.addEdges(newEdges)
        newPlan.validate()
        return newPlan
    }

    private fun mapRetainedMetricSpecsByNode(
        queryGraph: MetricEvaluationPlan,
        rootQueryNode: MetricQueryNode,
        metricSpecsToRetainForRootQueryNode: Set<MetricSpec>,
    ): Map<MetricQueryNode, OrderedSet<MetricSpec>> {
        val nodesToProcess: MutableOrderedSet<MetricQueryNode> = MutableOrderedSet(listOf(rootQueryNode))
        val nodeToRetained = LinkedHashMap<MetricQueryNode, MutableOrderedSet<MetricSpec>>()
        nodeToRetained.getOrPut(rootQueryNode) { MutableOrderedSet() }
            .addAll(metricSpecsToRetainForRootQueryNode)

        while (nodesToProcess.isNotEmpty()) {
            val current = nodesToProcess.first()
            nodesToProcess.remove(current)

            val retainedForCurrent = nodeToRetained.getOrPut(current) { MutableOrderedSet() }
            for (edge in queryGraph.sourceEdges(current)) {
                val requiredOutputSpec = edge.targetNodeOutputSpec
                if (requiredOutputSpec in retainedForCurrent) {
                    nodeToRetained.getOrPut(edge.headNode) { MutableOrderedSet() }
                        .add(edge.sourceNodeOutputSpec)
                    nodesToProcess.add(edge.headNode)
                }
            }
        }

        return nodeToRetained.mapValues { (_, v) -> FrozenOrderedSet(v) }
    }

    private fun generateSubplanForRecursiveMetricsAtSingleLevel(
        outputQueryElements: OrderedSet<MetricQueryElement>,
        candidateInputQueryNodes: List<MetricQueryNode>,
        queryElementLookup: MetricQueryElementLookup,
        queryElementToLevel: Map<MetricQueryElement, Int>,
    ): Pair<OrderedSet<DerivedMetricsQueryNode>, OrderedSet<MetricQueryDependencyEdge>> {
        val sorted = outputQueryElements.toList().sortedByDescending {
            queryElementLookup.getInputQueryElements(it).size
        }

        val querySetSelector = BestMetricQuerySetSelector(queryElementToLevel)

        val nodes: MutableOrderedSet<DerivedMetricsQueryNode> = MutableOrderedSet()
        val edges: MutableOrderedSet<MetricQueryDependencyEdge> = MutableOrderedSet()

        for (outputElement in sorted) {
            val inputElements: OrderedSet<MetricQueryElement> = FrozenOrderedSet(
                queryElementLookup.getInputQueryElements(outputElement),
            )

            val result = querySetSelector.findBestQueries(
                desiredQueryElements = inputElements,
                candidateInputNodes = candidateInputQueryNodes,
            )

            if (result.remainingDesiredQueryElements.isNotEmpty()) {
                throw MetricFlowInternalError(
                    "Unable to find a query element group that can satisfy the inputs. " +
                        "inputQueryElements=$inputElements result=$result",
                )
            }

            val computedMetricSpec = outputElement.metricSpec
            var passthroughMetricSpecs: List<MetricSpec> = emptyList()

            if (metricSpecAllowsPassthrough(computedMetricSpec)) {
                passthroughMetricSpecs = result.inputQueryNodeToFulfilledQueryElements.keys
                    .flatMap { it.outputMetricSpecs.toList() }
                    .filter { metricSpecAllowsPassthrough(it) }
                    .distinct()
            }

            val derivedNode = DerivedMetricsQueryNode.create(
                computedMetricSpecs = listOf(outputElement.metricSpec),
                passthroughMetricSpecs = passthroughMetricSpecs,
                queryProperties = outputElement.queryProperties,
            )

            nodes.add(derivedNode)

            val passthroughSet = passthroughMetricSpecs.toSet()
            val passthroughEdgeAdded = mutableSetOf<MetricSpec>()
            for ((inputQueryNode, fulfilledElements) in result.inputQueryNodeToFulfilledQueryElements) {
                for (inputMetricSpec in inputQueryNode.outputMetricSpecs) {
                    if (inputMetricSpec in passthroughSet) {
                        edges.add(
                            MetricQueryDependencyEdge.create(
                                targetNode = derivedNode,
                                targetNodeOutputSpec = inputMetricSpec,
                                sourceNode = inputQueryNode,
                                sourceNodeOutputSpec = inputMetricSpec,
                            ),
                        )
                        passthroughEdgeAdded.add(inputMetricSpec)
                    }
                }
                for (fulfilledElement in fulfilledElements) {
                    edges.add(
                        MetricQueryDependencyEdge.create(
                            targetNode = derivedNode,
                            targetNodeOutputSpec = outputElement.metricSpec,
                            sourceNode = inputQueryNode,
                            sourceNodeOutputSpec = fulfilledElement.metricSpec,
                        ),
                    )
                }
            }

            val missingPassthrough = passthroughSet - passthroughEdgeAdded
            if (missingPassthrough.isNotEmpty()) {
                throw MetricFlowInternalError(
                    "Missing passthrough spec in edges: missingPassthrough=$missingPassthrough",
                )
            }
        }
        return nodes to edges
    }

    private fun recursivelyCollectQueryElements(
        queryElement: MetricQueryElement,
        queryElementCollector: MetricQueryElementCollector,
        filterSpecFactory: WhereFilterSpecFactory,
    ) {
        if (queryElement in queryElementCollector.queryElements) return

        val metricSpec = queryElement.metricSpec
        val metricName = metricSpec.elementName
        val metric = manifestObjectLookup.getMetric(metricName)
        val metricType = metric.type

        when (metricType) {
            MetricType.SIMPLE, MetricType.CUMULATIVE, MetricType.CONVERSION -> {
                queryElementCollector.addQueryElement(queryElement, null)
            }
            MetricType.RATIO, MetricType.DERIVED -> {
                var additionalFilterSpecs: Iterable<WhereFilterSpec> = metricSpec.whereFilterSpecs
                var groupBySpecsForInputs: Iterable<LinkableInstanceSpec> = queryElement.groupByItemSpecs
                var predicatePushdownStateForInputs = queryElement.predicatePushdownState

                if (metricSpec.hasTimeOffset) {
                    groupBySpecsForInputs = queryHelper.resolveGroupBySpecsForTimeOffsetMetricInput(
                        queriedGroupBySpecs = queryElement.groupByItemSpecs,
                        filterSpecs = metricSpec.whereFilterSpecs,
                    )
                    predicatePushdownStateForInputs = PredicatePushdownState.withPushdownDisabled()
                    additionalFilterSpecs = emptyList()
                }

                val inputMetricSpecs = buildInputMetricSpecsForDerivedMetric(
                    metricName = metricName,
                    additionalFilterSpecs = additionalFilterSpecs,
                    filterSpecFactory = filterSpecFactory,
                )

                val inputQueryElements = inputMetricSpecs.map { inputSpec ->
                    val element = MetricQueryElement.create(
                        metricSpec = inputSpec,
                        groupByItemSpecs = groupBySpecsForInputs,
                        predicatePushdownState = predicatePushdownStateForInputs,
                    )
                    recursivelyCollectQueryElements(
                        queryElement = element,
                        queryElementCollector = queryElementCollector,
                        filterSpecFactory = filterSpecFactory,
                    )
                    element
                }

                queryElementCollector.addQueryElement(queryElement, inputQueryElements)
            }
        }
    }

    private fun groupQueryElementsByLevel(
        queryElementLookup: MetricQueryElementLookup,
    ): Map<Int, OrderedSet<MetricQueryElement>> {
        val collected = LinkedHashMap<Int, MutableOrderedSet<MetricQueryElement>>()
        for (queryElement in queryElementLookup.queryElements) {
            val level = levelResolver.resolveEvaluationLevel(queryElement.metricName)
            collected.getOrPut(level) { MutableOrderedSet() }.add(queryElement)
        }

        if (collected.isEmpty()) {
            throw MetricFlowInternalError("Expected at least one query element when grouping by level.")
        }

        val sorted = collected.entries.sortedBy { it.key }
        val out = LinkedHashMap<Int, OrderedSet<MetricQueryElement>>()
        for ((level, elements) in sorted) out[level] = FrozenOrderedSet(elements)

        // Levels must be contiguous from 0 to max(level).
        val sortedLevels = out.keys.toList()
        val maxLevel = sortedLevels.last()
        val expected = (0..maxLevel).toList()
        if (sortedLevels != expected) {
            throw MetricFlowInternalError(
                "Expected evaluation levels to be contiguous from 0. " +
                    "actual=$sortedLevels expected=$expected",
            )
        }

        return out
    }

    companion object {
        /** A metric spec can be safely passed through iff it has no aliasing or offset. */
        internal fun metricSpecAllowsPassthrough(metricSpec: MetricSpec): Boolean =
            metricSpec.offsetWindow == null && metricSpec.offsetToGrain == null && metricSpec.alias == null
    }
}
