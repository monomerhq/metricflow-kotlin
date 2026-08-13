package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.bind.SqlTable

/**
 * Sink node where incoming data gets written to a SQL table.
 *
 * Port of `metricflow.dataflow.nodes.write_to_table.WriteToResultTableNode`.
 *
 * Note: Python reuses the `DATAFLOW_NODE_WRITE_TO_RESULT_DATA_TABLE_ID_PREFIX` (i.e. the same
 * `wrd_*` prefix as [WriteToResultDataTableNode]) — that's a known Python quirk, not a Kotlin
 * port issue. We preserve it for snapshot parity.
 *
 * @property outputSqlTable The table where the computed metrics should be written.
 */
class WriteToResultTableNode(
    parentNode: DataflowPlanNode,
    val outputSqlTable: SqlTable,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Write to Table"

    // Intentionally reuses the data-table prefix to match Python `write_to_table.py:id_prefix`.
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_WRITE_TO_RESULT_DATA_TABLE_ID_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitWriteToResultTableNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is WriteToResultTableNode && other.outputSqlTable == outputSqlTable

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): WriteToResultTableNode =
        WriteToResultTableNode(parentNode = newParentNodes[0], outputSqlTable = outputSqlTable)
}
