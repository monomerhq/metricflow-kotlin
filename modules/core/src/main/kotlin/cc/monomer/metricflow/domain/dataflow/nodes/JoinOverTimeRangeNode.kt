package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow

/**
 * Enables cumulative metric computation via a self-join across a cumulative date range.
 *
 * Port of `metricflow.dataflow.nodes.join_over_time.JoinOverTimeRangeNode`.
 *
 * Exactly one of [window] or [grainToDate] may be set — the constructor raises otherwise.
 *
 * @property queriedAggTimeDimensionSpecs Time dimension specs that will be selected from the spine.
 * @property window Time window to join over.
 * @property grainToDate Time-range starts at the beginning of this granularity (e.g. month→day).
 * @property timeRangeConstraint Time range to aggregate over.
 */
class JoinOverTimeRangeNode(
    parentNode: DataflowPlanNode,
    val queriedAggTimeDimensionSpecs: List<TimeDimensionSpec>,
    val window: TimeWindow?,
    val grainToDate: TimeGranularity?,
    val timeRangeConstraint: TimeRangeConstraint?,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    init {
        if (window != null && grainToDate != null) {
            error(
                "This node cannot be initialized with both window and grain_to_date set. " +
                    "This configuration should have been prevented by model validation. " +
                    "window: $window. grain_to_date: $grainToDate.",
            )
        }
    }

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Join Self Over Time Range"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_JOIN_SELF_OVER_TIME_RANGE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            add(DisplayedProperty("queried_agg_time_dimension_specs", queriedAggTimeDimensionSpecs))
            if (window != null) add(DisplayedProperty("window", window))
            if (grainToDate != null) add(DisplayedProperty("grain_to_date", grainToDate))
            if (timeRangeConstraint != null) add(DisplayedProperty("time_range_constraint", timeRangeConstraint))
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitJoinOverTimeRangeNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is JoinOverTimeRangeNode &&
            other.grainToDate == grainToDate &&
            other.window == window &&
            other.timeRangeConstraint == timeRangeConstraint &&
            other.queriedAggTimeDimensionSpecs == queriedAggTimeDimensionSpecs

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): JoinOverTimeRangeNode {
        check(newParentNodes.size == 1) {
            "JoinOverTimeRangeNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return JoinOverTimeRangeNode(
            parentNode = newParentNodes[0],
            queriedAggTimeDimensionSpecs = queriedAggTimeDimensionSpecs,
            window = window,
            grainToDate = grainToDate,
            timeRangeConstraint = timeRangeConstraint,
        )
    }
}
