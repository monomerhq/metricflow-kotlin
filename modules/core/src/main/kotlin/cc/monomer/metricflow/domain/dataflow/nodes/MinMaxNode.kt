package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor

/**
 * Calculates the min and max of a single-instance dataset.
 *
 * Port of `metricflow.dataflow.nodes.min_max.MinMaxNode`. Carries no state.
 */
class MinMaxNode(parentNode: DataflowPlanNode) :
    DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Calculate min and max"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_MIN_MAX_ID_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R = visitor.visitMinMaxNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean = other is MinMaxNode

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): MinMaxNode {
        check(newParentNodes.size == 1) {
            "MinMaxNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return MinMaxNode(newParentNodes[0])
    }
}
