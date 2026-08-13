package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.OrderBySpec

/**
 * Re-orders the input data, optionally limiting the row count.
 *
 * Port of `metricflow.dataflow.nodes.order_by_limit.OrderByLimitNode`.
 *
 * @property orderBySpecs Describes how to order the incoming data.
 * @property limit Maximum number of rows to emit. `null` means unlimited.
 */
class OrderByLimitNode(
    parentNode: DataflowPlanNode,
    val orderBySpecs: List<OrderBySpec>,
    val limit: Int?,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String
        get() {
            // Python `repr()` produces `['name']` with single quotes around each name. Mirror
            // that exactly so the explain SQL header lines match the snapshot fixtures.
            val nameList = orderBySpecs.joinToString(
                separator = ", ", prefix = "[", postfix = "]",
            ) { "'${it.instanceSpec.dunderName}'" }
            val orderStr = "Order By $nameList"
            return if (limit != null) "$orderStr Limit $limit" else orderStr
        }

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_ORDER_BY_LIMIT_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            for (spec in orderBySpecs) add(DisplayedProperty("order_by_spec", spec))
            add(DisplayedProperty("limit", limit.toString()))
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitOrderByLimitNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is OrderByLimitNode &&
            other.orderBySpecs == orderBySpecs &&
            other.limit == limit

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): OrderByLimitNode {
        check(newParentNodes.size == 1) {
            "OrderByLimitNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return OrderByLimitNode(
            parentNode = newParentNodes[0],
            orderBySpecs = orderBySpecs,
            limit = limit,
        )
    }
}
