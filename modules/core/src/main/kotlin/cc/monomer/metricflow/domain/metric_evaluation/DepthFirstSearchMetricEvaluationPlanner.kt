package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.metric_evaluation.plan.ConversionMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.CumulativeMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.DerivedMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryDependencyEdge
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElement
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryPropertySet
import cc.monomer.metricflow.domain.metric_evaluation.plan.MutableMetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.SimpleMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.TopLevelQueryNode
import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * The default [MetricEvaluationPlanner] — builds a plan via iterative depth-
 * first traversal of the metric dependency graph.
 *
 * Port of `metricflow.metric_evaluation.dfs_me_planner.DepthFirstSearchMetricEvaluationPlanner`.
 *
 * For example, the plan for the query `[bookings_per_listing, bookings]` is:
 *
 *     MetricQuery([bookings_per_listing]) -> MetricQuery([bookings])
 *     MetricQuery([bookings_per_listing]) -> MetricQuery([listings])
 *     TopLevelQuery -> MetricQuery([bookings_per_listing])
 *     TopLevelQuery -> MetricQuery([bookings])
 *
 * This mirrors the original approach to compute metrics in the
 * [cc.monomer.metricflow.domain.dataflow.builder.DataflowPlanBuilder].
 */
class DepthFirstSearchMetricEvaluationPlanner(
    manifestObjectLookup: ManifestObjectLookup,
    metricLookup: MetricLookup,
    columnAssociationResolver: ColumnAssociationResolver,
) : MetricEvaluationPlanner(manifestObjectLookup, metricLookup, columnAssociationResolver) {

    override fun buildPlan(
        metricSpecs: List<MetricSpec>,
        groupByItemSpecs: List<LinkableInstanceSpec>,
        predicatePushdownState: PredicatePushdownState,
        filterSpecFactory: WhereFilterSpecFactory,
    ): MetricEvaluationPlan {
        metricLookup.validateMetricDefinitionDependencies(
            rootMetricReferences = metricSpecs.map { it.reference },
            maximumMetricLevels = MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH,
        )

        val topLevelQueryElements = metricSpecs.map { metricSpec ->
            MetricQueryElement.create(
                metricSpec = metricSpec,
                groupByItemSpecs = groupByItemSpecs,
                predicatePushdownState = predicatePushdownState,
            )
        }

        val evaluationPlan = MutableMetricEvaluationPlan.create()

        // Iterative DFS — the work-stack holds elements to process. The next element is popped
        // from the right, so we add elements in reverse to preserve traversal order.
        val workStack: ArrayDeque<MetricQueryElement> = ArrayDeque<MetricQueryElement>().apply {
            for (element in topLevelQueryElements.reversed()) addLast(element)
        }
        val queryElementToNode = LinkedHashMap<MetricQueryElement, MetricQueryNode>()

        while (workStack.isNotEmpty()) {
            val currentQueryElement = workStack.removeLast()
            if (currentQueryElement in queryElementToNode) continue

            val currentMetricSpec = currentQueryElement.metricSpec
            val currentQueryProperties = currentQueryElement.queryProperties
            val currentPredicatePushdownState = currentQueryElement.predicatePushdownState

            val metricName = currentMetricSpec.elementName
            val metric = manifestObjectLookup.getMetric(metricName)

            // Handle non-derived metrics — they translate directly to a base node.
            val baseNode = createBaseMetricQueryNode(
                metric = metric,
                metricType = metric.type,
                metricSpec = currentMetricSpec,
                queryProperties = currentQueryProperties,
            )
            if (baseNode != null) {
                evaluationPlan.addNode(baseNode)
                queryElementToNode[currentQueryElement] = baseNode
                continue
            }

            // Handle derived / ratio metrics — schedule their inputs and revisit.
            val inputQueryElements = inputMetricQueryElementsForDerivedMetric(
                metricSpec = currentMetricSpec,
                groupByItemSpecs = currentQueryElement.groupByItemSpecs,
                predicatePushdownState = currentPredicatePushdownState,
                filterSpecFactory = filterSpecFactory,
            )
            check(inputQueryElements.isNotEmpty()) {
                "Expected a ratio or derived metric to have input query elements " +
                    "(currentMetricSpec=$currentMetricSpec, metric=$metric)"
            }

            val inputsThatNeedProcessing =
                inputQueryElements.filter { it !in queryElementToNode }

            if (inputsThatNeedProcessing.isNotEmpty()) {
                // Re-queue current node after its inputs.
                workStack.addLast(currentQueryElement)
                for (input in inputsThatNeedProcessing.reversed()) {
                    workStack.addLast(input)
                }
                continue
            }

            // All inputs ready — emit the derived metric node and its edges.
            val derivedNode = DerivedMetricsQueryNode.create(
                computedMetricSpecs = listOf(currentMetricSpec),
                passthroughMetricSpecs = emptyList(),
                queryProperties = currentQueryProperties,
            )
            evaluationPlan.addNode(derivedNode)

            for (inputQueryElement in inputQueryElements) {
                val inputNode = queryElementToNode[inputQueryElement]
                    ?: throw MetricFlowInternalError(
                        "Input query element should have been processed: $inputQueryElement",
                    )
                evaluationPlan.addEdge(
                    MetricQueryDependencyEdge.create(
                        targetNode = derivedNode,
                        targetNodeOutputSpec = currentMetricSpec,
                        sourceNode = inputNode,
                        sourceNodeOutputSpec = inputQueryElement.metricSpec,
                    ),
                )
            }

            queryElementToNode[currentQueryElement] = derivedNode
        }

        // After every requested metric has a node, attach a top-level query node.
        val topLevelQueryNode = TopLevelQueryNode.create(
            passthroughMetricSpecs = metricSpecs,
            queryProperties = MetricQueryPropertySet.create(
                groupByItemSpecs = groupByItemSpecs,
                predicatePushdownState = predicatePushdownState,
            ),
        )
        evaluationPlan.addNode(topLevelQueryNode)

        for (topLevelElement in topLevelQueryElements) {
            val producingNode = queryElementToNode[topLevelElement]
                ?: throw MetricFlowInternalError(
                    "Top-level query element was not produced by any node: $topLevelElement",
                )
            evaluationPlan.addEdge(
                MetricQueryDependencyEdge.create(
                    targetNode = topLevelQueryNode,
                    targetNodeOutputSpec = topLevelElement.metricSpec,
                    sourceNode = producingNode,
                    sourceNodeOutputSpec = topLevelElement.metricSpec,
                ),
            )
        }

        return evaluationPlan
    }

    /**
     * Return the node for a base (non-derived) metric, or `null` if the metric
     * is a derived / ratio metric and requires dependency expansion.
     */
    private fun createBaseMetricQueryNode(
        metric: Metric,
        metricType: MetricType,
        metricSpec: MetricSpec,
        queryProperties: MetricQueryPropertySet,
    ): MetricQueryNode? = when (metricType) {
        MetricType.SIMPLE -> {
            val params = metric.typeParams.metricAggregationParams
                ?: throw IllegalStateException(
                    "Simple metric is missing metric aggregation parameters: " +
                        "metricSpec=$metricSpec, metric=$metric",
                )
            SimpleMetricsQueryNode.create(
                modelId = SemanticModelId.getInstance(params.semanticModel),
                metricSpecs = listOf(metricSpec),
                queryProperties = queryProperties,
            )
        }
        MetricType.CUMULATIVE -> CumulativeMetricQueryNode.create(
            metricSpec = metricSpec,
            queryProperties = queryProperties,
        )
        MetricType.CONVERSION -> ConversionMetricQueryNode.create(
            metricSpec = metricSpec,
            queryProperties = queryProperties,
        )
        MetricType.RATIO, MetricType.DERIVED -> null
    }

    /**
     * Return the input [MetricQueryElement]s for a ratio / derived metric.
     *
     * Time-offset inputs broaden the group-by set and disable predicate
     * pushdown so the offset can be applied before filtering.
     */
    private fun inputMetricQueryElementsForDerivedMetric(
        metricSpec: MetricSpec,
        groupByItemSpecs: Iterable<LinkableInstanceSpec>,
        predicatePushdownState: PredicatePushdownState,
        filterSpecFactory: WhereFilterSpecFactory,
    ): List<MetricQueryElement> {
        var additionalFilterSpecs: Iterable<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec> =
            metricSpec.whereFilterSpecs
        var groupByItemSpecsForInputs: Iterable<LinkableInstanceSpec> = groupByItemSpecs
        var predicatePushdownStateForInputs = predicatePushdownState

        if (metricSpec.hasTimeOffset) {
            groupByItemSpecsForInputs = queryHelper.resolveGroupBySpecsForTimeOffsetMetricInput(
                queriedGroupBySpecs = groupByItemSpecs,
                filterSpecs = metricSpec.whereFilterSpecs,
            )
            predicatePushdownStateForInputs = PredicatePushdownState.withPushdownDisabled()
            // For offset metrics the WHERE clause is applied after offset, so it must not be passed
            // down to input metric specs. Time-range constraint is applied by INNER JOINing to the
            // time spine instead.
            additionalFilterSpecs = emptyList()
        }

        val inputMetricSpecs = buildInputMetricSpecsForDerivedMetric(
            metricName = metricSpec.elementName,
            additionalFilterSpecs = additionalFilterSpecs,
            filterSpecFactory = filterSpecFactory,
        )

        return inputMetricSpecs.map { inputSpec ->
            MetricQueryElement.create(
                metricSpec = inputSpec,
                groupByItemSpecs = groupByItemSpecsForInputs,
                predicatePushdownState = predicatePushdownStateForInputs,
            )
        }
    }
}
