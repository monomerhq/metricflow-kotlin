package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping

/**
 * Aggregates the simple-metric inputs by the associated group-by elements.
 *
 * Port of `metricflow.dataflow.nodes.aggregate_simple_metric_inputs.AggregateSimpleMetricInputsNode`.
 *
 * The output instances of this node contain context on the appropriate null-fill value. The
 * downstream [ComputeMetricsNode] uses this context to render the appropriate `COALESCE`
 * expressions over the aggregates.
 */
class AggregateSimpleMetricInputsNode(
    parentNode: DataflowPlanNode,
    /**
     * Should contain an entry for **each** simple-metric input so that the
     * `ComputeMetricsBranchCombiner` (W9b) can detect conflicts. Port of Python field
     * `null_fill_value_mapping`.
     */
    val nullFillValueMapping: NullFillValueMapping,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Aggregate Inputs for Simple Metrics"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_AGGREGATE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("null_fill_value_mapping", nullFillValueMapping.elementNameToNullFillValue)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitAggregateSimpleMetricInputsNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is AggregateSimpleMetricInputsNode && other.nullFillValueMapping == nullFillValueMapping

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): AggregateSimpleMetricInputsNode {
        check(newParentNodes.size == 1) {
            "AggregateSimpleMetricInputsNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return AggregateSimpleMetricInputsNode(
            parentNode = newParentNodes[0],
            nullFillValueMapping = nullFillValueMapping,
        )
    }
}
