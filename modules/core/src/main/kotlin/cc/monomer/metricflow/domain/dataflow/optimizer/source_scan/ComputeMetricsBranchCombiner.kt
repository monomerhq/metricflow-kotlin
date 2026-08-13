package cc.monomer.metricflow.domain.dataflow.optimizer.source_scan

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.dataflow.nodes.AddGeneratedUuidColumnNode
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.AliasSpecsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinConversionEventsNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToTimeSpineNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.MinMaxNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetBaseGrainByCustomGrainNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.SemiAdditiveJoinNode
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.dataflow.nodes.WindowReaggregationNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode

/**
 * Result of a [ComputeMetricsBranchCombiner] visit.
 *
 * Port of
 * `metricflow.dataflow.optimizer.source_scan.cm_branch_combiner.ComputeMetricsBranchCombinerResult`.
 *
 * If [combinedBranch] is `null`, combination could not occur — propagated up through the recursion
 * to indicate failure at any level.
 */
data class ComputeMetricsBranchCombinerResult(val combinedBranch: DataflowPlanNode?) {
    val combined: Boolean get() = combinedBranch != null

    val checkedCombinedBranch: DataflowPlanNode
        get() = checkNotNull(combinedBranch) { "ComputeMetricsBranchCombinerResult had no combined branch" }

    companion object {
        /** Convenience: "could not combine" result. */
        val NOT_COMBINED: ComputeMetricsBranchCombinerResult = ComputeMetricsBranchCombinerResult(null)
    }
}

/**
 * Visitor that combines two dataflow branches whose leaf is a [ComputeMetricsNode].
 *
 * Port of
 * `metricflow.dataflow.optimizer.source_scan.cm_branch_combiner.ComputeMetricsBranchCombiner`.
 *
 * Consider two branches, a **left** branch (supplied to the constructor) and a **right** branch
 * (supplied via `accept`). The combiner attempts to construct a third branch that is the
 * superposition of the two — same structure, same linkable specs at each level, but a union of
 * the simple-metric inputs / metrics being aggregated. The recursive walk pairs each left node
 * with the corresponding right node; if any level cannot be combined, the failure propagates up
 * and the overall result is `null`.
 *
 * The handler for each node type decides whether combining is possible — see the per-`visit*`
 * methods below. The same caching strategy as Python is used: `(left, right) → result` is memoised
 * via an external [CombinerResultCache].
 */
class ComputeMetricsBranchCombiner(
    leftBranchNode: DataflowPlanNode,
    private val branchCombinerCache: CombinerResultCache,
) : DataflowPlanNodeVisitor<ComputeMetricsBranchCombinerResult> {

    private var currentLeftNode: DataflowPlanNode = leftBranchNode

    /**
     * Combine the parents of the current left node with the parents of [currentRightNode]
     * recursively. Returns the combined parent list, or `null` if combination failed.
     */
    private fun combineParentBranches(currentRightNode: DataflowPlanNode): List<DataflowPlanNode>? {
        if (currentLeftNode.parentNodes.size != currentRightNode.parentNodes.size) return null

        val visitResults = mutableListOf<ComputeMetricsBranchCombinerResult>()
        for ((i, rightParent) in currentRightNode.parentNodes.withIndex()) {
            val savedLeft = currentLeftNode
            currentLeftNode = currentLeftNode.parentNodes[i]
            visitResults.add(rightParent.accept(this))
            currentLeftNode = savedLeft
        }

        val combinedParents = mutableListOf<DataflowPlanNode>()
        for (result in visitResults) {
            val combined = result.combinedBranch ?: return null
            combinedParents.add(combined)
        }
        return combinedParents
    }

    /** Default handler: combine if parents combined and left equals or is functionally identical to right. */
    private fun defaultHandler(currentRightNode: DataflowPlanNode): ComputeMetricsBranchCombinerResult {
        val key = currentLeftNode to currentRightNode
        val cached = branchCombinerCache.get(key)
        if (cached != null) return cached
        val result = defaultHandlerInner(currentRightNode)
        branchCombinerCache.set(key, result)
        return result
    }

    private fun defaultHandlerInner(currentRightNode: DataflowPlanNode): ComputeMetricsBranchCombinerResult {
        val combinedParents = combineParentBranches(currentRightNode) ?: return ComputeMetricsBranchCombinerResult.NOT_COMBINED

        if (currentLeftNode == currentRightNode) {
            return ComputeMetricsBranchCombinerResult(currentLeftNode)
        }
        if (currentLeftNode.functionallyIdentical(currentRightNode)) {
            return ComputeMetricsBranchCombinerResult(currentRightNode.withNewParents(combinedParents))
        }
        return ComputeMetricsBranchCombinerResult.NOT_COMBINED
    }

    override fun visitSourceNode(node: ReadSqlSourceNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitAggregateSimpleMetricInputsNode(
        node: AggregateSimpleMetricInputsNode,
    ): ComputeMetricsBranchCombinerResult {
        val key = currentLeftNode to node
        val cached = branchCombinerCache.get(key)
        if (cached != null) return cached
        val result = visitAggregateSimpleMetricInputsNodeInner(node)
        branchCombinerCache.set(key, result)
        return result
    }

    private fun visitAggregateSimpleMetricInputsNodeInner(
        node: AggregateSimpleMetricInputsNode,
    ): ComputeMetricsBranchCombinerResult {
        val combinedParents = combineParentBranches(node) ?: return ComputeMetricsBranchCombinerResult.NOT_COMBINED
        val left = currentLeftNode
        if (left !is AggregateSimpleMetricInputsNode) return ComputeMetricsBranchCombinerResult.NOT_COMBINED

        check(combinedParents.size == 1)
        val combinedParent = combinedParents[0]

        if (left.nullFillValueMapping.hasConflict(node.nullFillValueMapping)) {
            return ComputeMetricsBranchCombinerResult.NOT_COMBINED
        }

        val merged = AggregateSimpleMetricInputsNode(
            parentNode = combinedParent,
            nullFillValueMapping = left.nullFillValueMapping.merge(node.nullFillValueMapping),
        )
        return ComputeMetricsBranchCombinerResult(merged)
    }

    override fun visitComputeMetricsNode(node: ComputeMetricsNode): ComputeMetricsBranchCombinerResult {
        val key = currentLeftNode to node
        val cached = branchCombinerCache.get(key)
        if (cached != null) return cached
        val result = visitComputeMetricsNodeInner(node)
        branchCombinerCache.set(key, result)
        return result
    }

    private fun visitComputeMetricsNodeInner(node: ComputeMetricsNode): ComputeMetricsBranchCombinerResult {
        val combinedParents = combineParentBranches(node) ?: return ComputeMetricsBranchCombinerResult.NOT_COMBINED
        val left = currentLeftNode
        if (left !is ComputeMetricsNode) return ComputeMetricsBranchCombinerResult.NOT_COMBINED

        val (canCombine, _) = left.canCombine(node)
        if (!canCombine) return ComputeMetricsBranchCombinerResult.NOT_COMBINED

        check(combinedParents.size == 1)
        val combinedParent = combinedParents[0]

        // Dedup preserving order, mirroring Python's FrozenOrderedSet usage.
        val dedupedComputed = LinkedHashSet<cc.monomer.metricflow.domain.spec.MetricSpec>().apply {
            addAll(left.computedMetricSpecs)
            addAll(node.computedMetricSpecs)
        }.toList()
        val dedupedPassthrough = LinkedHashSet<cc.monomer.metricflow.domain.spec.MetricSpec>().apply {
            addAll(left.passthroughMetricSpecs)
            addAll(node.passthroughMetricSpecs)
        }.toList()

        val combined = ComputeMetricsNode.create(
            parentNode = combinedParent,
            computedMetricSpecs = dedupedComputed,
            passthroughMetricSpecs = dedupedPassthrough,
            aggregatedToElements = node.aggregatedToElements,
            outputGroupByMetricInstances = node.outputGroupByMetricInstances,
        )
        return ComputeMetricsBranchCombinerResult(combined)
    }

    /** Common "right node type is not yet supported" fallback. */
    private fun handleUnsupported(): ComputeMetricsBranchCombinerResult = ComputeMetricsBranchCombinerResult.NOT_COMBINED

    override fun visitWindowReaggregationNode(node: WindowReaggregationNode): ComputeMetricsBranchCombinerResult =
        handleUnsupported()

    override fun visitOrderByLimitNode(node: OrderByLimitNode): ComputeMetricsBranchCombinerResult =
        handleUnsupported()

    override fun visitWhereConstraintNode(node: WhereFilterNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitWriteToResultDataTableNode(
        node: WriteToResultDataTableNode,
    ): ComputeMetricsBranchCombinerResult = handleUnsupported()

    override fun visitWriteToResultTableNode(node: WriteToResultTableNode): ComputeMetricsBranchCombinerResult =
        handleUnsupported()

    override fun visitSelectorNode(node: SelectorNode): ComputeMetricsBranchCombinerResult {
        val key = currentLeftNode to node
        val cached = branchCombinerCache.get(key)
        if (cached != null) return cached
        val result = visitSelectorNodeInner(node)
        branchCombinerCache.set(key, result)
        return result
    }

    private fun visitSelectorNodeInner(node: SelectorNode): ComputeMetricsBranchCombinerResult {
        val combinedParents = combineParentBranches(node) ?: return ComputeMetricsBranchCombinerResult.NOT_COMBINED
        val left = currentLeftNode
        if (left !is SelectorNode) return ComputeMetricsBranchCombinerResult.NOT_COMBINED

        check(combinedParents.size == 1)
        val combinedParent = combinedParents[0]

        // Linkable specs must match for SelectorNode combination.
        if (!MatchingLinkableSpecsTransform(left.includeSpecs).transform(node.includeSpecs)) {
            return ComputeMetricsBranchCombinerResult.NOT_COMBINED
        }

        val merged = SelectorNode(
            parentNode = combinedParent,
            includeSpecs = left.includeSpecs.merge(node.includeSpecs).dedupe(),
            replaceDescription = node.replaceDescription,
            distinct = node.distinct,
        )
        return ComputeMetricsBranchCombinerResult(merged)
    }

    override fun visitCombineAggregatedOutputsNode(
        node: CombineAggregatedOutputsNode,
    ): ComputeMetricsBranchCombinerResult = handleUnsupported()

    override fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitMetricTimeDimensionTransformNode(
        node: MetricTimeDimensionTransformNode,
    ): ComputeMetricsBranchCombinerResult = defaultHandler(node)

    override fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitAddGeneratedUuidColumnNode(
        node: AddGeneratedUuidColumnNode,
    ): ComputeMetricsBranchCombinerResult = defaultHandler(node)

    override fun visitJoinConversionEventsNode(node: JoinConversionEventsNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitJoinToCustomGranularityNode(
        node: JoinToCustomGranularityNode,
    ): ComputeMetricsBranchCombinerResult = defaultHandler(node)

    override fun visitMinMaxNode(node: MinMaxNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitAliasSpecsNode(node: AliasSpecsNode): ComputeMetricsBranchCombinerResult =
        defaultHandler(node)

    override fun visitOffsetBaseGrainByCustomGrainNode(
        node: OffsetBaseGrainByCustomGrainNode,
    ): ComputeMetricsBranchCombinerResult = defaultHandler(node)

    override fun visitOffsetCustomGranularityNode(
        node: OffsetCustomGranularityNode,
    ): ComputeMetricsBranchCombinerResult = defaultHandler(node)
}

/**
 * Simple memoisation cache for `(left, right) → ComputeMetricsBranchCombinerResult`. Port of
 * Python `ResultCache[tuple[DataflowPlanNode, DataflowPlanNode], ComputeMetricsBranchCombinerResult]`.
 */
class CombinerResultCache {
    private val store: MutableMap<Pair<DataflowPlanNode, DataflowPlanNode>, ComputeMetricsBranchCombinerResult> =
        HashMap()

    fun get(key: Pair<DataflowPlanNode, DataflowPlanNode>): ComputeMetricsBranchCombinerResult? = store[key]

    fun set(
        key: Pair<DataflowPlanNode, DataflowPlanNode>,
        value: ComputeMetricsBranchCombinerResult,
    ) {
        store[key] = value
    }
}
