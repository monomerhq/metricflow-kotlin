package cc.monomer.metricflow.domain.sql.plan.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * A `CREATE TABLE <sql_table> AS <parent_node>` statement.
 *
 * Port of `metricflow.sql.sql_ctas_node.SqlCreateTableAsNode`. The node has exactly one
 * parent (the `<select>` to populate the new table).
 */
class SqlCreateTableAsNode(
    val sqlTable: SqlTable,
    parentNode: SqlPlanNode,
) : SqlPlanNode(listOf(parentNode)) {

    /** The single parent SELECT (or other producing) node. */
    val parentNode: SqlPlanNode get() = parentNodes[0]

    override val description: String get() = "Create table '${sqlTable.sql}'"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_PLAN_CREATE_TABLE_AS_ID_PREFIX

    override fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R =
        visitor.visitCreateTableAsNode(this)

    override val asSelectNode: SqlSelectStatementNode? get() = null
    override val asSqlTableNode: SqlTableNode? get() = null

    override fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn>? =
        parentNode.nearestSelectColumns(cteSourceMapping)

    override fun copyNode(): SqlCreateTableAsNode = create(
        sqlTable = sqlTable,
        parentNode = parentNode.copyNode(),
    )

    companion object {
        fun create(sqlTable: SqlTable, parentNode: SqlPlanNode): SqlCreateTableAsNode =
            SqlCreateTableAsNode(sqlTable, parentNode)
    }
}
