package cc.monomer.metricflow.infrastructure.sql.render.redshift

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpressionArgument
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RedshiftSqlPlanRendererTest {

    private val planRenderer = RedshiftSqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

    @Test
    fun `double data type is DOUBLE PRECISION`() {
        assertEquals("DOUBLE PRECISION", exprRenderer.doubleDataType)
    }

    @Test
    fun `supports continuous and approximate discrete percentile`() {
        assertEquals(
            setOf(
                SqlPercentileFunctionType.CONTINUOUS,
                SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
            ),
            exprRenderer.supportedPercentileFunctionTypes,
        )
    }

    @Test
    fun `percentile continuous uses PERCENTILE_CONT WITHIN GROUP`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.CONTINUOUS,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY (events.value))", result.sql)
    }

    @Test
    fun `percentile approximate discrete uses APPROXIMATE PERCENTILE_DISC`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("APPROXIMATE PERCENTILE_DISC(0.5) WITHIN GROUP (ORDER BY (events.value))", result.sql)
    }

    @Test
    fun `discrete percentile is unsupported`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.DISCRETE,
            ),
        )
        assertFailsWith<UnsupportedEngineFeatureError> { exprRenderer.renderSqlExpr(expr) }
    }

    @Test
    fun `approximate continuous percentile is unsupported`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
        )
        assertFailsWith<UnsupportedEngineFeatureError> { exprRenderer.renderSqlExpr(expr) }
    }

    @Test
    fun `EXTRACT DOW renormalises Sunday from 0 to 7`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOW, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "CASE WHEN EXTRACT(dow FROM events.created_at) = 0 THEN EXTRACT(dow FROM events.created_at) + 7 ELSE EXTRACT(dow FROM events.created_at) END",
            result.sql,
        )
    }

    @Test
    fun `EXTRACT YEAR is left alone`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.YEAR, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(year FROM events.created_at)", result.sql)
    }

    @Test
    fun `generate uuid uses RANDOM-concat hack`() {
        val result = exprRenderer.renderSqlExpr(SqlGenerateUuidExpression.create())
        assertEquals(
            "CONCAT(CAST(RANDOM()*100000000 AS INT)::VARCHAR,CAST(RANDOM()*100000000 AS INT)::VARCHAR)",
            result.sql,
        )
    }

    @Test
    fun `plain SELECT renders as ANSI`() {
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
        assertContains(planRenderer.renderSqlPlan(plan).sql, "FROM ana.events events")
    }
}
