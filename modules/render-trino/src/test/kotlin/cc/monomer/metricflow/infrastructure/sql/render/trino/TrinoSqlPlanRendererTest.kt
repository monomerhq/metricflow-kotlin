package cc.monomer.metricflow.infrastructure.sql.render.trino

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlBetweenExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpressionArgument
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringLiteralExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrinoSqlPlanRendererTest {

    private val planRenderer = TrinoSqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

    @Test
    fun `renders a minimal SELECT identical to ANSI`() {
        val plan = SqlPlan(
            SqlSelectStatementNode.create(
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
            ),
        )
        val sql = planRenderer.renderSqlPlan(plan).sql
        assertContains(sql, "FROM ana.events events")
    }

    @Test
    fun `generate uuid uses Trino lowercase uuid()`() {
        val result = exprRenderer.renderSqlExpr(SqlGenerateUuidExpression.create())
        assertEquals("uuid()", result.sql)
    }

    @Test
    fun `EXTRACT uses DAY_OF_WEEK as the date part for DOW (Trino quirk)`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOW, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(DAY_OF_WEEK FROM events.created_at)", result.sql)
    }

    @Test
    fun `EXTRACT uses ANSI date part name for YEAR`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.YEAR, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(year FROM events.created_at)", result.sql)
    }

    @Test
    fun `subtract time interval uses DATE_ADD with quoted granularity and negated count`() {
        val arg = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlSubtractTimeIntervalExpression.create(arg = arg, count = 7, granularity = TimeGranularity.DAY)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("DATE_ADD('day', -7, events.created_at)", result.sql)
    }

    @Test
    fun `subtract time interval expands QUARTER into MONTH multiplied by 3`() {
        val arg = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlSubtractTimeIntervalExpression.create(arg = arg, count = 2, granularity = TimeGranularity.QUARTER)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("DATE_ADD('month', -6, events.created_at)", result.sql)
    }

    @Test
    fun `percentile approximate continuous renders as approx_percentile`() {
        val arg = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = arg,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("approx_percentile(events.value, 0.5)", result.sql)
    }

    @Test
    fun `percentile discrete is unsupported for Trino`() {
        val arg = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = arg,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.DISCRETE,
            ),
        )
        assertFailsWith<RuntimeException> { exprRenderer.renderSqlExpr(expr) }
    }

    @Test
    fun `between wraps quoted timestamp literals with timestamp prefix`() {
        val column = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val start = SqlStringLiteralExpression.create("2020-01-01")
        val end = SqlStringLiteralExpression.create("2020-12-31")
        val expr = SqlBetweenExpression.create(columnArg = column, startExpr = start, endExpr = end)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "events.created_at BETWEEN timestamp '2020-01-01' AND timestamp '2020-12-31'",
            result.sql,
        )
    }

    @Test
    fun `between does not wrap non-timestamp expressions`() {
        val column = SqlColumnReferenceExpression.fromColumnReference("events", "amount")
        val start = SqlIntegerExpression.create(1)
        val end = SqlIntegerExpression.create(10)
        val expr = SqlBetweenExpression.create(columnArg = column, startExpr = start, endExpr = end)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("events.amount BETWEEN 1 AND 10", result.sql)
    }

    @Test
    fun `supports only approximate continuous percentile`() {
        assertEquals(
            setOf(SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS),
            exprRenderer.supportedPercentileFunctionTypes,
        )
    }

    @Test
    fun `bindParameterSet is empty for plain selects`() {
        val plan = SqlPlan(
            SqlSelectStatementNode.create(
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
            ),
        )
        assertEquals(SqlBindParameterSet.EMPTY, planRenderer.renderSqlPlan(plan).bindParameterSet)
    }

    @Test
    fun `expression renderer shares the plan renderer's singleton`() {
        // Mirrors Python's `EXPR_RENDERER = TrinoSqlExpressionRenderer()` class-level constant.
        assertTrue(TrinoSqlPlanRenderer.EXPR_RENDERER is TrinoSqlExpressionRenderer)
        assertEquals(TrinoSqlPlanRenderer.EXPR_RENDERER, TrinoSqlPlanRenderer().exprRenderer)
    }
}
