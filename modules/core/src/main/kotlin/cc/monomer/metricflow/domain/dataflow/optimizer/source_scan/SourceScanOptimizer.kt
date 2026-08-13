package cc.monomer.metricflow.domain.dataflow.optimizer.source_scan

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlan
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
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimizer

/** Holds the result of running [SourceScanOptimizer] on a single branch. */
data class OptimizeBranchResult(val optimizedBranch: DataflowPlanNode)

/**
 * Tracks the result of merging a right-branch with one of the left-branches.
 *
 * Port of `metricflow.dataflow.optimizer.source_scan.source_scan_optimizer.BranchCombinationResult`.
 */
data class BranchCombinationResult(
    val leftBranch: DataflowPlanNode,
    val rightBranch: DataflowPlanNode,
    val combinedBranch: DataflowPlanNode?,
)

/**
 * Reduces the number of scans in a dataflow plan by combining parent branches of a
 * `CombineAggregatedOutputsNode`.
 *
 * Port of
 * `metricflow.dataflow.optimizer.source_scan.source_scan_optimizer.SourceScanOptimizer`.
 *
 * The pass traverses the plan via DFS; at each non-leaf node it first optimizes the parents,
 * then asks the `ComputeMetricsBranchCombiner` whether any of those optimized parents can be
 * merged. If all parents merge into one, the `CombineAggregatedOutputsNode` can be elided.
 */
class SourceScanOptimizer :
    DataflowPlanNodeVisitor<OptimizeBranchResult>,
    DataflowPlanOptimizer {

    private val nodeToResult: MutableMap<DataflowPlanNode, OptimizeBranchResult> = HashMap()
    private val branchCombinerCache: CombinerResultCache = CombinerResultCache()

    private fun defaultBaseOutputHandler(node: DataflowPlanNode): OptimizeBranchResult {
        nodeToResult[node]?.let { return it }
        val optimizedParents: List<DataflowPlanNode> =
            node.parentNodes.map { it.accept(this).optimizedBranch }
        val result = if (node.parentNodes == optimizedParents) {
            OptimizeBranchResult(node)
        } else {
            OptimizeBranchResult(node.withNewParents(optimizedParents))
        }
        nodeToResult[node] = result
        return result
    }

    override fun visitSourceNode(node: ReadSqlSourceNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitAggregateSimpleMetricInputsNode(
        node: AggregateSimpleMetricInputsNode,
    ): OptimizeBranchResult = defaultBaseOutputHandler(node)

    override fun visitWindowReaggregationNode(node: WindowReaggregationNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitComputeMetricsNode(node: ComputeMetricsNode): OptimizeBranchResult {
        nodeToResult[node]?.let { return it }
        val optimizedParent = node.parentNode.accept(this).optimizedBranch
        val result = OptimizeBranchResult(
            ComputeMetricsNode.create(
                parentNode = optimizedParent,
                computedMetricSpecs = node.computedMetricSpecs,
                passthroughMetricSpecs = node.passthroughMetricSpecs,
                outputGroupByMetricInstances = node.outputGroupByMetricInstances,
                aggregatedToElements = node.aggregatedToElements,
            ),
        )
        nodeToResult[node] = result
        return result
    }

    override fun visitOrderByLimitNode(node: OrderByLimitNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitWhereConstraintNode(node: WhereFilterNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitWriteToResultDataTableNode(node: WriteToResultDataTableNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitWriteToResultTableNode(node: WriteToResultTableNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitSelectorNode(node: SelectorNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    private fun combineBranches(
        leftBranches: List<DataflowPlanNode>,
        rightBranch: DataflowPlanNode,
    ): List<BranchCombinationResult> {
        val results = mutableListOf<BranchCombinationResult>()
        var combined = false
        for (leftBranch in leftBranches) {
            if (!combined) {
                val combiner = ComputeMetricsBranchCombiner(
                    leftBranchNode = leftBranch,
                    branchCombinerCache = branchCombinerCache,
                )
                val combinerResult = rightBranch.accept(combiner)
                if (combinerResult.combinedBranch != null) {
                    combined = true
                    results.add(
                        BranchCombinationResult(
                            leftBranch = leftBranch,
                            rightBranch = rightBranch,
                            combinedBranch = combinerResult.combinedBranch,
                        ),
                    )
                    continue
                }
            }
            results.add(BranchCombinationResult(leftBranch = leftBranch, rightBranch = rightBranch, combinedBranch = null))
        }
        return results
    }

    override fun visitCombineAggregatedOutputsNode(node: CombineAggregatedOutputsNode): OptimizeBranchResult {
        nodeToResult[node]?.let { return it }

        val optimizedParents = node.parentNodes.map { it.accept(this).optimizedBranch }

        // Greedy N² combination, mirroring Python.
        var combinedParents: List<DataflowPlanNode> = emptyList()
        for (optimizedParent in optimizedParents) {
            val combinationResults = combineBranches(leftBranches = combinedParents, rightBranch = optimizedParent)
            val anyCombined = combinationResults.any { it.combinedBranch != null }
            combinedParents = if (!anyCombined) {
                combinedParents + optimizedParent
            } else {
                combinationResults.map { r -> r.combinedBranch ?: r.leftBranch }
            }
        }

        check(combinedParents.isNotEmpty())

        val result = if (combinedParents.size == 1) {
            OptimizeBranchResult(combinedParents[0])
        } else {
            OptimizeBranchResult(CombineAggregatedOutputsNode(parentNodes = combinedParents))
        }
        nodeToResult[node] = result
        return result
    }

    override fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitMetricTimeDimensionTransformNode(
        node: MetricTimeDimensionTransformNode,
    ): OptimizeBranchResult = defaultBaseOutputHandler(node)

    override fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitAddGeneratedUuidColumnNode(node: AddGeneratedUuidColumnNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitJoinConversionEventsNode(node: JoinConversionEventsNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitJoinToCustomGranularityNode(
        node: JoinToCustomGranularityNode,
    ): OptimizeBranchResult = defaultBaseOutputHandler(node)

    override fun visitMinMaxNode(node: MinMaxNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitAliasSpecsNode(node: AliasSpecsNode): OptimizeBranchResult =
        defaultBaseOutputHandler(node)

    override fun visitOffsetBaseGrainByCustomGrainNode(
        node: OffsetBaseGrainByCustomGrainNode,
    ): OptimizeBranchResult = defaultBaseOutputHandler(node)

    override fun visitOffsetCustomGranularityNode(
        node: OffsetCustomGranularityNode,
    ): OptimizeBranchResult = defaultBaseOutputHandler(node)

    override fun optimize(dataflowPlan: DataflowPlan): DataflowPlan {
        val optimizedResult = dataflowPlan.sinkNode.accept(this)
        return DataflowPlan(
            renderNode = optimizedResult.optimizedBranch,
            planId = DagId.fromIdPrefix(StaticIdPrefix.OPTIMIZED_DATAFLOW_PLAN_PREFIX),
        )
    }
}
