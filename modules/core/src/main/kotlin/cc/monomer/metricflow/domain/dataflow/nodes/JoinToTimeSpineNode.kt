package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType

/**
 * Joins the parent dataset to a time-spine dataset.
 *
 * Port of `metricflow.dataflow.nodes.join_to_time_spine.JoinToTimeSpineNode`.
 *
 * Parent ordering is `(metricSourceNode, timeSpineNode)` — exactly Python's order.
 *
 * @property requestedAggTimeDimensionSpecs Time dimensions requested in the query.
 * @property joinOnTimeDimensionSpec The time dimension to use in the join `ON` condition.
 * @property joinType Join type for the time-spine join.
 * @property standardOffsetWindow Time window to offset the parent dataset by when joining. Must
 *   use a **standard** granularity (custom-grain offsets go to [OffsetCustomGranularityNode]).
 * @property offsetToGrain Granularity period to offset the parent dataset to when joining.
 */
class JoinToTimeSpineNode(
    val metricSourceNode: DataflowPlanNode,
    val timeSpineNode: DataflowPlanNode,
    val requestedAggTimeDimensionSpecs: List<TimeDimensionSpec>,
    val joinOnTimeDimensionSpec: TimeDimensionSpec,
    val joinType: SqlJoinType,
    val standardOffsetWindow: TimeWindow?,
    val offsetToGrain: TimeGranularity?,
) : DataflowPlanNode(parentNodes = listOf(metricSourceNode, timeSpineNode)) {

    init {
        check(!(standardOffsetWindow != null && offsetToGrain != null)) {
            "Can't set both standard_offset_window and offset_to_grain when joining to time spine. " +
                "Choose one or the other."
        }
        check(requestedAggTimeDimensionSpecs.isNotEmpty()) {
            "Must have at least one value in requested_agg_time_dimension_specs for JoinToTimeSpineNode."
        }
        if (standardOffsetWindow != null && !standardOffsetWindow.isStandardGranularity) {
            error(
                "JoinToTimeSpineNode should not accept a custom standard_offset_window. " +
                    "Got: $standardOffsetWindow",
            )
        }
    }

    override val description: String get() = "Join to Time Spine Dataset"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_JOIN_TO_TIME_SPINE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            add(DisplayedProperty("requested_agg_time_dimension_specs", requestedAggTimeDimensionSpecs))
            add(DisplayedProperty("join_on_time_dimension_spec", joinOnTimeDimensionSpec))
            add(DisplayedProperty("join_type", joinType))
            if (standardOffsetWindow != null) add(DisplayedProperty("standard_offset_window", standardOffsetWindow))
            if (offsetToGrain != null) add(DisplayedProperty("offset_to_grain", offsetToGrain))
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitJoinToTimeSpineNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is JoinToTimeSpineNode &&
            other.standardOffsetWindow == standardOffsetWindow &&
            other.offsetToGrain == offsetToGrain &&
            other.requestedAggTimeDimensionSpecs == requestedAggTimeDimensionSpecs &&
            other.joinOnTimeDimensionSpec == joinOnTimeDimensionSpec &&
            other.joinType == joinType

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): JoinToTimeSpineNode {
        check(newParentNodes.size == 2) {
            "JoinToTimeSpineNode expects exactly two parents (metric source + time spine). " +
                "Got: ${newParentNodes.size}"
        }
        return JoinToTimeSpineNode(
            metricSourceNode = newParentNodes[0],
            timeSpineNode = newParentNodes[1],
            requestedAggTimeDimensionSpecs = requestedAggTimeDimensionSpecs,
            joinOnTimeDimensionSpec = joinOnTimeDimensionSpec,
            joinType = joinType,
            standardOffsetWindow = standardOffsetWindow,
            offsetToGrain = offsetToGrain,
        )
    }
}
