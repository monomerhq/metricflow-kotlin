package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparison
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparisonExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringLiteralExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DefaultSqlPlanRendererTest {

    private val renderer = DefaultSqlPlanRenderer()

    @Test
    fun `renders a minimal SELECT with a single column from a table`() {
        val node = SqlSelectStatementNode.create(
            description = "",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "id")),
            fromSource = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events")),
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val plan = SqlPlan(node)
        val result = renderer.renderSqlPlan(plan)

        // The collapse rule kicks in: "events.id AS id" → "events.id" when there are no joins.
        assertEquals(
            """
            SELECT
              events.id
            FROM ana.events events
            """.trimIndent(),
            result.sql,
        )
        assertEquals(SqlBindParameterSet.EMPTY, result.bindParameterSet)
    }

    @Test
    fun `renders SELECT DISTINCT with WHERE and LIMIT`() {
        val table = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events"))
        val node = SqlSelectStatementNode.create(
            description = "",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "user_id")),
            fromSource = table,
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = SqlComparisonExpression.create(
                leftExpr = SqlColumnReferenceExpression.fromColumnReference("events", "active"),
                comparison = SqlComparison.EQUALS,
                rightExpr = SqlIntegerExpression.create(1),
            ),
            limit = 5,
            distinct = true,
        )

        val result = renderer.renderSqlPlan(SqlPlan(node))
        assertContains(result.sql, "SELECT DISTINCT")
        assertContains(result.sql, "WHERE events.active = 1")
        assertContains(result.sql, "LIMIT 5")
    }

    @Test
    fun `renders comment description as -- prefixed lines`() {
        val node = SqlSelectStatementNode.create(
            description = "line one\nline two",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "id")),
            fromSource = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events")),
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val result = renderer.renderSqlPlan(SqlPlan(node))
        assertTrue(result.sql.startsWith("-- line one\n-- line two\n"), "Got: ${result.sql}")
    }

    @Test
    fun `renders a string literal expression with single quotes`() {
        val exprResult = renderer.exprRenderer.renderSqlExpr(SqlStringLiteralExpression.create("foo"))
        assertEquals("'foo'", exprResult.sql)
    }

    @Test
    fun `renders a string expression as-is`() {
        val expr = SqlStringExpression.create(
            sqlExpr = "1 + 1",
            bindParameterSet = SqlBindParameterSet.EMPTY,
            requiresParenthesis = false,
            usedColumns = null,
        )
        val result = renderer.exprRenderer.renderSqlExpr(expr)
        assertEquals("1 + 1", result.sql)
    }
}
