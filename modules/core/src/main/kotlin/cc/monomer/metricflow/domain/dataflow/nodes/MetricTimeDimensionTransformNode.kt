package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference

/**
 * Transforms the input dataset so it contains the `metric_time` dimension and relevant simple-metric inputs.
 *
 * Port of `metricflow.dataflow.nodes.metric_time_transform.MetricTimeDimensionTransformNode`.
 *
 * **Input**: a dataset containing simple-metric inputs and the aggregation time dimension.
 *
 * **Output**: similar to the input, but augmented with the configured aggregation time dimension
 * appearing as `metric_time`. Only simple-metric inputs defined to use this aggregation time
 * dimension are passed through.
 *
 * The ID prefix is `sma` (Set Metric Aggregation), preserving the Python static-id mapping.
 */
class MetricTimeDimensionTransformNode(
    parentNode: DataflowPlanNode,
    val aggregationTimeDimensionReference: TimeDimensionReference,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String
        get() = "Metric Time Dimension '${aggregationTimeDimensionReference.elementName}'"

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_SET_METRIC_AGGREGATION_TIME

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("aggregation_time_dimension", aggregationTimeDimensionReference.elementName)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitMetricTimeDimensionTransformNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is MetricTimeDimensionTransformNode &&
            other.aggregationTimeDimensionReference == aggregationTimeDimensionReference

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): MetricTimeDimensionTransformNode {
        check(newParentNodes.size == 1) {
            "MetricTimeDimensionTransformNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return MetricTimeDimensionTransformNode(
            parentNode = newParentNodes[0],
            aggregationTimeDimensionReference = aggregationTimeDimensionReference,
        )
    }
}
