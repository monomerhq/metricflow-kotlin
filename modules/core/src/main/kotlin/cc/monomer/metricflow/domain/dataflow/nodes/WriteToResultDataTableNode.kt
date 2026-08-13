package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor

/**
 * Sink node where incoming data gets written to an in-memory `DataTable`.
 *
 * Port of `metricflow.dataflow.nodes.write_to_data_table.WriteToResultDataTableNode`. Carries
 * no state — used by `explain()` callers that want results materialised in memory rather than
 * persisted to a database table.
 */
class WriteToResultDataTableNode(parentNode: DataflowPlanNode) :
    DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Write to DataTable"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_WRITE_TO_RESULT_DATA_TABLE_ID_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitWriteToResultDataTableNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is WriteToResultDataTableNode

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): WriteToResultDataTableNode {
        check(newParentNodes.size == 1) {
            "WriteToResultDataTableNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return WriteToResultDataTableNode(newParentNodes[0])
    }
}
