package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Joins the parent dataset to a time-spine dataset to convert a time dimension to a custom granularity.
 *
 * Port of `metricflow.dataflow.nodes.join_to_custom_granularity.JoinToCustomGranularityNode`.
 *
 * @property timeDimensionSpec The time dimension spec with a custom granularity that this node
 *   satisfies. Must carry a custom grain (the constructor enforces this — internal
 *   misconfiguration otherwise).
 */
class JoinToCustomGranularityNode(
    parentNode: DataflowPlanNode,
    val timeDimensionSpec: TimeDimensionSpec,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    init {
        check(timeDimensionSpec.hasCustomGrain) {
            "Time granularity for time dimension spec in JoinToCustomGranularityNode must be qualified as " +
                "custom granularity. Instead, found ${timeDimensionSpec.timeGranularityName}. " +
                "This indicates internal misconfiguration."
        }
    }

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Join to Custom Granularity Dataset"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_JOIN_TO_CUSTOM_GRANULARITY_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("time_dimension_spec", timeDimensionSpec)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitJoinToCustomGranularityNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is JoinToCustomGranularityNode && other.timeDimensionSpec == timeDimensionSpec

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): JoinToCustomGranularityNode {
        check(newParentNodes.size == 1) {
            "JoinToCustomGranularityNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return JoinToCustomGranularityNode(
            parentNode = newParentNodes[0],
            timeDimensionSpec = timeDimensionSpec,
        )
    }
}
