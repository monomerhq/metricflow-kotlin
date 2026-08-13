package cc.monomer.metricflow.infrastructure.sql.render.databricks

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpressionArgument
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DatabricksSqlPlanRendererTest {

    private val planRenderer = DatabricksSqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

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
    fun `EXTRACT DOW uses DAYOFWEEK_ISO`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOW, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(DAYOFWEEK_ISO FROM events.created_at)", result.sql)
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
    fun `percentile approximate discrete uses APPROX_PERCENTILE`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("APPROX_PERCENTILE(events.value, 0.5)", result.sql)
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
