package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow

/**
 * Offsets the base grain of a custom grain by a number of custom-grain periods.
 *
 * Port of `metricflow.dataflow.nodes.offset_base_grain_by_custom_grain.OffsetBaseGrainByCustomGrainNode`.
 *
 * Used to build the time-spine node when a metric query has a custom-grain offset window with
 * mixed grains/date-parts. This node satisfies **base grains** only — custom grains are joined
 * later in the dataflow plan. The custom grain's base grain must always be present in
 * [requiredTimeSpineSpecs] because it's the join key back to the source node.
 *
 * If the metric is queried with ONLY the same grain as is used in the offset window, callers
 * should use [OffsetCustomGranularityNode] instead.
 */
class OffsetBaseGrainByCustomGrainNode(
    val timeSpineNode: DataflowPlanNode,
    val offsetWindow: TimeWindow,
    val requiredTimeSpineSpecs: List<TimeDimensionSpec>,
) : DataflowPlanNode(parentNodes = listOf(timeSpineNode)) {

    init {
        for (spec in requiredTimeSpineSpecs) {
            if (spec.hasCustomGrain) {
                error(
                    "Found custom grain in required specs, which is not supported by " +
                        "OffsetBaseGrainByCustomGrainNode. required_time_spine_specs=$requiredTimeSpineSpecs",
                )
            }
        }
        if (offsetWindow.isStandardGranularity) {
            error(
                "OffsetBaseGrainByCustomGrainNode should only be used for custom grain offset windows. " +
                    "offset_window=$offsetWindow",
            )
        }
    }

    override val description: String get() = "Offset Base Granularity By Custom Granularity Period(s)"

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_OFFSET_BY_CUSTOM_GRANULARITY_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("offset_window", offsetWindow) +
            DisplayedProperty("required_time_spine_specs", requiredTimeSpineSpecs)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitOffsetBaseGrainByCustomGrainNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is OffsetBaseGrainByCustomGrainNode &&
            other.offsetWindow == offsetWindow &&
            other.requiredTimeSpineSpecs == requiredTimeSpineSpecs

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): OffsetBaseGrainByCustomGrainNode {
        check(newParentNodes.size == 1) {
            "OffsetBaseGrainByCustomGrainNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return OffsetBaseGrainByCustomGrainNode(
            timeSpineNode = newParentNodes[0],
            offsetWindow = offsetWindow,
            requiredTimeSpineSpecs = requiredTimeSpineSpecs,
        )
    }
}
