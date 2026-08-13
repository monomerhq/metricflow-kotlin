package cc.monomer.metricflow.infrastructure.sql.render.postgres

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpressionArgument
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PostgresSqlPlanRendererTest {

    private val planRenderer = PostgresSqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

    @Test
    fun `double data type is DOUBLE PRECISION`() {
        assertEquals("DOUBLE PRECISION", exprRenderer.doubleDataType)
    }

    @Test
    fun `supports continuous and discrete percentile`() {
        assertEquals(
            setOf(SqlPercentileFunctionType.CONTINUOUS, SqlPercentileFunctionType.DISCRETE),
            exprRenderer.supportedPercentileFunctionTypes,
        )
    }

    @Test
    fun `subtract time interval uses MAKE_INTERVAL with keyword arg`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlSubtractTimeIntervalExpression.create(arg = col, count = 7, granularity = TimeGranularity.DAY)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("events.created_at - MAKE_INTERVAL(days => 7)", result.sql)
    }

    @Test
    fun `subtract time interval expands QUARTER to MONTH times 3`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlSubtractTimeIntervalExpression.create(arg = col, count = 2, granularity = TimeGranularity.QUARTER)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("events.created_at - MAKE_INTERVAL(months => 6)", result.sql)
    }

    @Test
    fun `add time uses MAKE_INTERVAL with INTEGER cast`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlAddTimeExpression.create(arg = col, countExpr = SqlIntegerExpression.create(3), granularity = TimeGranularity.DAY)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("events.created_at + MAKE_INTERVAL(days => CAST (3 AS INTEGER))", result.sql)
    }

    @Test
    fun `generate uuid uses GEN_RANDOM_UUID`() {
        val result = exprRenderer.renderSqlExpr(SqlGenerateUuidExpression.create())
        assertEquals("GEN_RANDOM_UUID()", result.sql)
    }

    @Test
    fun `percentile continuous uses PERCENTILE_CONT`() {
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
    fun `percentile discrete uses PERCENTILE_DISC`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.9,
                functionType = SqlPercentileFunctionType.DISCRETE,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("PERCENTILE_DISC(0.9) WITHIN GROUP (ORDER BY (events.value))", result.sql)
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
    fun `approximate discrete percentile is unsupported`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
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
