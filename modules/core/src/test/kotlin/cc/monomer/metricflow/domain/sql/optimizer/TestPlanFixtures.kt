package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Helper builders for assembling small SQL plan fixtures used by optimizer unit tests.
 * Test-only; kept in `:domain:sql:optimizer/src/test`.
 */
internal object TestPlanFixtures {

    /** Build a plain SELECT over a literal table. */
    fun simpleSelect(
        description: String = "test",
        selectColumns: List<SqlSelectColumn>,
        fromTable: SqlTableNode,
        fromAlias: String,
        where: SqlExpressionNode? = null,
        groupBys: List<SqlSelectColumn> = emptyList(),
        orderBys: List<SqlOrderByDescription> = emptyList(),
        limit: Int? = null,
        distinct: Boolean = false,
        joinDescs: List<SqlJoinDescription> = emptyList(),
    ): SqlSelectStatementNode = SqlSelectStatementNode.create(
        description = description,
        selectColumns = selectColumns,
        fromSource = fromTable,
        fromSourceAlias = fromAlias,
        cteSources = emptyList(),
        joinDescs = joinDescs,
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )

    /** Build a SELECT wrapping another SELECT in a sub-query — the standard sub-query shape. */
    fun selectFromSubquery(
        description: String = "outer",
        outerSelectColumns: List<SqlSelectColumn>,
        innerSelect: SqlPlanNode,
        innerAlias: String,
        where: SqlExpressionNode? = null,
        groupBys: List<SqlSelectColumn> = emptyList(),
        orderBys: List<SqlOrderByDescription> = emptyList(),
        limit: Int? = null,
        distinct: Boolean = false,
    ): SqlSelectStatementNode = SqlSelectStatementNode.create(
        description = description,
        selectColumns = outerSelectColumns,
        fromSource = innerSelect,
        fromSourceAlias = innerAlias,
        cteSources = emptyList(),
        joinDescs = emptyList(),
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )

    fun tableNode(schema: String, table: String): SqlTableNode =
        SqlTableNode.create(SqlTable(schemaName = schema, tableName = table))

    fun col(tableAlias: String, columnName: String): SqlColumnReferenceExpression =
        SqlColumnReferenceExpression.fromColumnReference(tableAlias = tableAlias, columnName = columnName)

    fun selectCol(tableAlias: String, columnName: String, asAlias: String = columnName): SqlSelectColumn =
        SqlSelectColumn(expr = col(tableAlias, columnName), columnAlias = asAlias)
}
