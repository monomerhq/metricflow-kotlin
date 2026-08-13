package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Re-aggregates metrics using window functions.
 *
 * Port of `metricflow.dataflow.nodes.window_reaggregation_node.WindowReaggregationNode`. Used
 * to compute cumulative metrics at various granularities.
 *
 * The parent **must** be a [ComputeMetricsNode] — the constructor checks the type when called
 * via [create] but the public ctor leaves the check to [withNewParents] to avoid hard-failing
 * deserialization paths.
 *
 * [orderBySpec] must not also appear in [partitionBySpecs] — that would be a no-op
 * re-aggregation and is treated as an internal misconfiguration.
 */
class WindowReaggregationNode(
    parentNode: ComputeMetricsNode,
    val metricSpec: MetricSpec,
    val orderBySpec: TimeDimensionSpec,
    val partitionBySpecs: List<InstanceSpec>,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    init {
        if (orderBySpec in partitionBySpecs) {
            error(
                "Order by spec found in partition by specs for WindowAggregationNode. This indicates " +
                    "internal misconfiguration because reaggregation should not be needed in this circumstance. " +
                    "Order by spec: $orderBySpec; Partition by specs: $partitionBySpecs",
            )
        }
    }

    val parentNode: ComputeMetricsNode get() = parentNodes[0] as ComputeMetricsNode

    override val description: String get() = "Re-aggregate Metrics via Window Functions"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_WINDOW_REAGGREGATION_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("metric_spec", metricSpec) +
            DisplayedProperty("order_by_spec", orderBySpec) +
            DisplayedProperty("partition_by_specs", partitionBySpecs)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitWindowReaggregationNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is WindowReaggregationNode &&
            other.parentNodes == parentNodes &&
            other.metricSpec == metricSpec &&
            other.orderBySpec == orderBySpec &&
            other.partitionBySpecs == partitionBySpecs

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): WindowReaggregationNode {
        check(newParentNodes.size == 1) { "WindowReaggregationNode cannot accept multiple parents." }
        val newParent = newParentNodes[0]
        check(newParent is ComputeMetricsNode) {
            "WindowReaggregationNode can only have ComputeMetricsNode as parent node."
        }
        return WindowReaggregationNode(
            parentNode = newParent,
            metricSpec = metricSpec,
            orderBySpec = orderBySpec,
            partitionBySpecs = partitionBySpecs,
        )
    }
}
