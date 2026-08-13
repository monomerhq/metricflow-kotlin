package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor

/**
 * Constrains the time range of the input dataset.
 *
 * Port of `metricflow.dataflow.nodes.constrain_time.ConstrainTimeRangeNode`.
 *
 * For example, if the input dataset is "sales by date", this node restricts the dataset so it
 * only includes sales for a specific date range.
 */
class ConstrainTimeRangeNode(
    parentNode: DataflowPlanNode,
    val timeRangeConstraint: TimeRangeConstraint,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String
        get() {
            // Python's `isoformat(timespec="seconds")` always shows `HH:MM:SS`. Kotlin's
            // `LocalDateTime.toString()` strips trailing zeros (e.g. `2020-01-01T00:00`), so use
            // a fixed-pattern formatter to match the snapshot fixtures.
            val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            val start = timeRangeConstraint.startTime.format(fmt)
            val end = timeRangeConstraint.endTime.format(fmt)
            return "Constrain Time Range to [$start, $end]"
        }

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_CONSTRAIN_TIME_RANGE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("time_range_start", timeRangeConstraint.startTime.toString()) +
            DisplayedProperty("time_range_end", timeRangeConstraint.endTime.toString())

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitConstrainTimeRangeNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is ConstrainTimeRangeNode && other.timeRangeConstraint == timeRangeConstraint

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): ConstrainTimeRangeNode {
        check(newParentNodes.size == 1) {
            "ConstrainTimeRangeNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return ConstrainTimeRangeNode(
            parentNode = newParentNodes[0],
            timeRangeConstraint = timeRangeConstraint,
        )
    }
}
