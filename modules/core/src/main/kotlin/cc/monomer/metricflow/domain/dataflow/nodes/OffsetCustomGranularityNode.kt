package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow

/**
 * Offsets a custom grain by the requested number of periods.
 *
 * Port of `metricflow.dataflow.nodes.offset_custom_granularity.OffsetCustomGranularityNode`.
 *
 * Used to build the time-spine node when a metric query has a custom-grain offset window AND
 * the query requests **only** the same grain as is used in the offset window. The node outputs
 * offset columns for every requested custom-grain spec plus a non-offset column for the base
 * grain (needed to join back to the source).
 */
class OffsetCustomGranularityNode(
    val timeSpineNode: DataflowPlanNode,
    val offsetWindow: TimeWindow,
    val requiredTimeSpineSpecs: List<TimeDimensionSpec>,
) : DataflowPlanNode(parentNodes = listOf(timeSpineNode)) {

    init {
        if (offsetWindow.isStandardGranularity) {
            // Python's error message reuses the OffsetBaseGrainByCustomGrainNode wording —
            // matching here for line-for-line parity.
            error(
                "OffsetBaseGrainByCustomGrainNode should only be used for custom grain offset windows. " +
                    "offset_window=$offsetWindow",
            )
        }
    }

    override val description: String get() = "Offset Custom Granularity"

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_OFFSET_CUSTOM_GRANULARITY_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("offset_window", offsetWindow) +
            DisplayedProperty("required_time_spine_specs", requiredTimeSpineSpecs)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitOffsetCustomGranularityNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is OffsetCustomGranularityNode &&
            other.offsetWindow == offsetWindow &&
            other.requiredTimeSpineSpecs == requiredTimeSpineSpecs

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): OffsetCustomGranularityNode {
        check(newParentNodes.size == 1) {
            "OffsetCustomGranularityNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return OffsetCustomGranularityNode(
            timeSpineNode = newParentNodes[0],
            offsetWindow = offsetWindow,
            requiredTimeSpineSpecs = requiredTimeSpineSpecs,
        )
    }
}
