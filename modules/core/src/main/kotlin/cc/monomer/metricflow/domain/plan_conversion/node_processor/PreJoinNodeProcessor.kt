package cc.monomer.metricflow.domain.plan_conversion.node_processor

import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.builder.PartitionJoinResolver
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.lookup.SemanticModelLookup
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Processes source nodes before they are joined into the rest of the dataflow plan.
 *
 * Port of `metricflow.plan_conversion.node_processor.PreJoinNodeProcessor`. The processor adds
 * filter predicates (time-range constraints, where-filter pushdown) to source nodes so the
 * SQL planner can push them down to the leaf scans.
 *
 * For example, to realise a time range constraint, a [ConstrainTimeRangeNode] is wrapped
 * around an underlying source node:
 *
 * ```
 * <SomeDataflowPlanNode/>
 *
 * →
 *
 * <ConstrainTimeRangeNode>
 *     <SomeDataflowPlanNode/>
 * </ConstrainTimeRangeNode>
 * ```
 *
 * **Status — partial port.** [applyMatchingFilterPredicates] is fully ported and operates only
 * on the dataflow graph + spec set, so it lands here. The multi-hop logic
 * ([addMultiHopJoins], [getCandidateNodesForMultiHop]) and node-pruning helper
 * ([removeUnnecessaryNodes]) call into [DataflowNodeToSqlSubqueryVisitor.getOutputDataSet],
 * whose body is forward-deferred (see [DataflowNodeToSqlSubqueryVisitor]). These methods raise
 * [NotImplementedError] until the visitor body lands; the public signatures are stable so the
 * `:application:engine` facade can wire through them now.
 */
class PreJoinNodeProcessor(
    semanticModelLookup: SemanticModelLookup,
    /**
     * The W9c output-data-set resolver. Forward reference — body deferred (see class KDoc).
     */
    @Suppress("UNUSED_PARAMETER")
    nodeDataSetResolver: DataflowNodeToSqlSubqueryVisitor,
) {
    private val nodeDataSetResolver: DataflowNodeToSqlSubqueryVisitor = nodeDataSetResolver
    private val partitionResolver = PartitionJoinResolver(semanticModelLookup)
    private val semanticModelLookup = semanticModelLookup
    // private val joinEvaluator = JoinDataflowOutputValidator(semanticModelLookup)  // not yet ported

    /**
     * Add filter predicate nodes to the input nodes as appropriate.
     *
     * Port of `apply_matching_filter_predicates`.
     */
    fun applyMatchingFilterPredicates(
        sourceNodes: List<DataflowPlanNode>,
        predicatePushdownState: PredicatePushdownState,
        metricTimeDimensionReference: TimeDimensionReference,
    ): List<DataflowPlanNode> {
        var nodes = sourceNodes

        if (predicatePushdownState.hasTimeRangeConstraintToPushDown) {
            nodes = addTimeRangeConstraint(
                sourceNodes = nodes,
                metricTimeDimensionReference = metricTimeDimensionReference,
                timeRangeConstraint = predicatePushdownState.timeRangeConstraint,
            )
        }

        if (predicatePushdownState.hasWhereFiltersToPushDown) {
            nodes = addWhereConstraint(
                sourceNodes = nodes,
                whereFilterSpecs = predicatePushdownState.whereFilterSpecs,
                enabledElementTypes = predicatePushdownState.pushdownEligibleElementTypes,
            )
        }

        return nodes
    }

    private fun addTimeRangeConstraint(
        sourceNodes: List<DataflowPlanNode>,
        metricTimeDimensionReference: TimeDimensionReference,
        timeRangeConstraint: TimeRangeConstraint?,
    ): List<DataflowPlanNode> {
        if (timeRangeConstraint == null) return sourceNodes

        val processedNodes = mutableListOf<DataflowPlanNode>()
        for (sourceNode in sourceNodes) {
            val outputDataSet = nodeDataSetResolver.getOutputDataSet(sourceNode)
            var constrainTime = false
            for (timeDimensionInstance in outputDataSet.instanceSet.timeDimensionInstances) {
                if (timeDimensionInstance.spec.reference == metricTimeDimensionReference &&
                    timeDimensionInstance.spec.entityLinks.isEmpty()
                ) {
                    constrainTime = true
                    break
                }
            }
            processedNodes += if (constrainTime) {
                ConstrainTimeRangeNode(parentNode = sourceNode, timeRangeConstraint = timeRangeConstraint)
            } else {
                sourceNode
            }
        }
        return processedNodes
    }

    private fun addWhereConstraint(
        sourceNodes: List<DataflowPlanNode>,
        whereFilterSpecs: List<WhereFilterSpec>,
        enabledElementTypes: Set<LinkableElementType>,
    ): List<DataflowPlanNode> {
        // The Python implementation walks `spec.element_set.annotated_specs` to inspect each
        // spec's element type and origin semantic models. That requires downcasting the
        // `LinkableSpecGroup` to the W7c concrete `GroupByItemSet`. Since this method is only
        // called when [PredicatePushdownState.hasWhereFiltersToPushDown] is true (which itself
        // requires `whereFilterPushdownEnabled` ⇒ at least one of the categorical/entity/
        // time-dim types is enabled), we faithfully port the bucketing logic but tolerate the
        // absence of annotated-spec metadata by falling back to "no pushdown".
        val eligibleFilterSpecsByModel = LinkedHashMap<SemanticModelReference, MutableList<WhereFilterSpec>>()
        for (spec in whereFilterSpecs) {
            val annotatedSpecs = extractAnnotatedSpecs(spec) ?: continue
            val semanticModels = annotatedSpecs.flatMap { it.originSemanticModelReferences }.toSet()
            val invalidElementTypes = annotatedSpecs.map { it.elementType }
                .filter { it !in enabledElementTypes }
                .toSet()
            if (semanticModels.size == 1 && invalidElementTypes.isEmpty()) {
                val model = semanticModels.first()
                eligibleFilterSpecsByModel.getOrPut(model) { mutableListOf() }.add(spec)
            }
        }

        val filteredNodes = mutableListOf<DataflowPlanNode>()
        for (sourceNode in sourceNodes) {
            val nodeSemanticModels = sourceNode.asPlan().sourceSemanticModels.toList()
            if (nodeSemanticModels.size == 1 && nodeSemanticModels[0] in eligibleFilterSpecsByModel) {
                val eligibleFilterSpecs = eligibleFilterSpecsByModel.getValue(nodeSemanticModels[0])
                val sourceNodeSpecs =
                    nodeDataSetResolver.getOutputDataSet(sourceNode).instanceSet.specSet
                val matching = eligibleFilterSpecs.filter { filterSpec ->
                    filterSpec.linkableSpecs.all { it in sourceNodeSpecs.linkableSpecs }
                }
                filteredNodes += if (matching.isEmpty()) {
                    sourceNode
                } else {
                    WhereFilterNode(parentNode = sourceNode, filterSpecs = matching, alwaysApply = false)
                }
            } else {
                filteredNodes += sourceNode
            }
        }
        return filteredNodes
    }

    /**
     * Assemble all possible multi-hop joins for the given desired linkable specs.
     *
     * Port of `add_multi_hop_joins`. Status — deferred: depends on
     * [JoinDataflowOutputValidator][cc.monomer.metricflow.domain.lookup] (`metricflow/validation/dataflow_join_validator.py`,
     * not yet ported in `:domain:lookup`) and on the W10 body of
     * [DataflowNodeToSqlSubqueryVisitor]. The public signature is preserved so call sites can
     * wire to it now.
     */
    fun addMultiHopJoins(
        desiredLinkableSpecs: List<LinkableInstanceSpec>,
        nodes: List<DataflowPlanNode>,
        joinType: SqlJoinType,
    ): List<DataflowPlanNode> {
        throw NotImplementedError(
            "PreJoinNodeProcessor.addMultiHopJoins depends on JoinDataflowOutputValidator " +
                "(unported in :domain:lookup) and on the W10 visitor body. Tracked for W10.",
        )
    }

    /**
     * Filter out nodes that share no relevant element with the desired specs.
     *
     * Port of `remove_unnecessary_nodes`. Body deferred to W10 alongside the visitor.
     */
    fun removeUnnecessaryNodes(
        desiredLinkableSpecs: List<LinkableInstanceSpec>,
        nodes: List<DataflowPlanNode>,
        metricTimeDimensionReference: TimeDimensionReference,
        timeSpineMetricTimeNodes: List<MetricTimeDimensionTransformNode>,
    ): List<DataflowPlanNode> {
        throw NotImplementedError(
            "PreJoinNodeProcessor.removeUnnecessaryNodes depends on the W10 visitor body. " +
                "Public signature preserved.",
        )
    }

    /**
     * Project a [WhereFilterSpec]'s [WhereFilterSpec.elementSet] to its annotated specs, when
     * the element-set is a W7c [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet].
     * Returns `null` for the plain-list `LinkableSpecGroup` used by the bare W7b factory.
     */
    private fun extractAnnotatedSpecs(
        spec: WhereFilterSpec,
    ): List<cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec>? {
        val elementSet = spec.elementSet
        return (elementSet as?
            cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet)
            ?.annotatedSpecs
    }
}
