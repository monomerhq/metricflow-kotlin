package cc.monomer.metricflow.domain.dataflow

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
 * Visitor over the [DataflowPlanNode] hierarchy.
 *
 * Port of `metricflow.dataflow.dataflow_plan_visitor.DataflowPlanNodeVisitor`.
 *
 * Follows the visitor pattern: every concrete [DataflowPlanNode] variant has a corresponding
 * `visit*` method here, called from [DataflowPlanNode.accept]. Kotlin's interface enforces
 * exhaustiveness — adding a new node variant without extending this interface produces a
 * compile error at every implementation site.
 *
 * Method names mirror the Python visitor 1:1 — note that the source node's accept dispatches
 * to [visitSourceNode] (not "visitReadSqlSourceNode"), and the WHERE filter node dispatches
 * to [visitWhereConstraintNode] (legacy name from before the filter was renamed).
 */
interface DataflowPlanNodeVisitor<R> {

    fun visitSourceNode(node: ReadSqlSourceNode): R

    fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode): R

    fun visitAggregateSimpleMetricInputsNode(node: AggregateSimpleMetricInputsNode): R

    fun visitComputeMetricsNode(node: ComputeMetricsNode): R

    fun visitWindowReaggregationNode(node: WindowReaggregationNode): R

    fun visitOrderByLimitNode(node: OrderByLimitNode): R

    fun visitWhereConstraintNode(node: WhereFilterNode): R

    fun visitWriteToResultDataTableNode(node: WriteToResultDataTableNode): R

    fun visitWriteToResultTableNode(node: WriteToResultTableNode): R

    fun visitSelectorNode(node: SelectorNode): R

    fun visitCombineAggregatedOutputsNode(node: CombineAggregatedOutputsNode): R

    fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode): R

    fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode): R

    fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode): R

    fun visitMetricTimeDimensionTransformNode(node: MetricTimeDimensionTransformNode): R

    fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode): R

    fun visitMinMaxNode(node: MinMaxNode): R

    fun visitAddGeneratedUuidColumnNode(node: AddGeneratedUuidColumnNode): R

    fun visitJoinConversionEventsNode(node: JoinConversionEventsNode): R

    fun visitJoinToCustomGranularityNode(node: JoinToCustomGranularityNode): R

    fun visitAliasSpecsNode(node: AliasSpecsNode): R

    fun visitOffsetBaseGrainByCustomGrainNode(node: OffsetBaseGrainByCustomGrainNode): R

    fun visitOffsetCustomGranularityNode(node: OffsetCustomGranularityNode): R
}

/**
 * Convenience subtype: every visit-method routes to [defaultHandler] unless overridden.
 *
 * Port of `metricflow.dataflow.dataflow_plan_visitor.DataflowPlanNodeVisitorWithDefaultHandler`.
 * Useful for analyzers and traversal visitors that share the same logic across variants — they
 * only override [defaultHandler] and let exhaustiveness fall through.
 */
abstract class DataflowPlanNodeVisitorWithDefaultHandler<R> : DataflowPlanNodeVisitor<R> {

    /** The single handler each `visit*` falls through to. */
    protected abstract fun defaultHandler(node: DataflowPlanNode): R

    override fun visitSourceNode(node: ReadSqlSourceNode): R = defaultHandler(node)
    override fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode): R = defaultHandler(node)
    override fun visitAggregateSimpleMetricInputsNode(node: AggregateSimpleMetricInputsNode): R =
        defaultHandler(node)
    override fun visitComputeMetricsNode(node: ComputeMetricsNode): R = defaultHandler(node)
    override fun visitWindowReaggregationNode(node: WindowReaggregationNode): R = defaultHandler(node)
    override fun visitOrderByLimitNode(node: OrderByLimitNode): R = defaultHandler(node)
    override fun visitWhereConstraintNode(node: WhereFilterNode): R = defaultHandler(node)
    override fun visitWriteToResultDataTableNode(node: WriteToResultDataTableNode): R =
        defaultHandler(node)
    override fun visitWriteToResultTableNode(node: WriteToResultTableNode): R = defaultHandler(node)
    override fun visitSelectorNode(node: SelectorNode): R = defaultHandler(node)
    override fun visitCombineAggregatedOutputsNode(node: CombineAggregatedOutputsNode): R =
        defaultHandler(node)
    override fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode): R = defaultHandler(node)
    override fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode): R = defaultHandler(node)
    override fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode): R = defaultHandler(node)
    override fun visitMetricTimeDimensionTransformNode(node: MetricTimeDimensionTransformNode): R =
        defaultHandler(node)
    override fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode): R = defaultHandler(node)
    override fun visitMinMaxNode(node: MinMaxNode): R = defaultHandler(node)
    override fun visitAddGeneratedUuidColumnNode(node: AddGeneratedUuidColumnNode): R =
        defaultHandler(node)
    override fun visitJoinConversionEventsNode(node: JoinConversionEventsNode): R = defaultHandler(node)
    override fun visitJoinToCustomGranularityNode(node: JoinToCustomGranularityNode): R =
        defaultHandler(node)
    override fun visitAliasSpecsNode(node: AliasSpecsNode): R = defaultHandler(node)
    override fun visitOffsetBaseGrainByCustomGrainNode(node: OffsetBaseGrainByCustomGrainNode): R =
        defaultHandler(node)
    override fun visitOffsetCustomGranularityNode(node: OffsetCustomGranularityNode): R =
        defaultHandler(node)
}
