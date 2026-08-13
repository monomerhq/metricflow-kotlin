package cc.monomer.metricflow.infrastructure.sql.render.bigquery

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlCastToTimestampExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
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

class BigQuerySqlPlanRendererTest {

    private val planRenderer = BigQuerySqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

    @Test
    fun `double data type is FLOAT64`() {
        assertEquals("FLOAT64", exprRenderer.doubleDataType)
    }

    @Test
    fun `timestamp data type is DATETIME`() {
        assertEquals("DATETIME", exprRenderer.timestampDataType)
    }

    @Test
    fun `supports only approximate continuous percentile`() {
        assertEquals(
            setOf(SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS),
            exprRenderer.supportedPercentileFunctionTypes,
        )
    }

    @Test
    fun `GROUP BY references the column alias not the expression`() {
        val node = SqlSelectStatementNode.create(
            description = "",
            selectColumns = listOf(SqlSelectColumn.fromColumnReference("events", "user_id")),
            fromSource = SqlTableNode.create(SqlTable(schemaName = "ana", tableName = "events")),
            fromSourceAlias = "events",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = listOf(SqlSelectColumn.fromColumnReference("events", "user_id")),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val sql = planRenderer.renderSqlPlan(SqlPlan(node)).sql
        assertContains(sql, "GROUP BY\n  user_id")
    }

    @Test
    fun `percentile uses APPROX_QUANTILES with fraction OFFSET`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        // Fraction(0.5).limit_denominator() == 1/2
        assertEquals("APPROX_QUANTILES(events.value, 2)[OFFSET(1)]", result.sql)
    }

    @Test
    fun `percentile fraction reduction handles 0_1`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.1,
                functionType = SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("APPROX_QUANTILES(events.value, 10)[OFFSET(1)]", result.sql)
    }

    @Test
    fun `continuous percentile is unsupported`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.CONTINUOUS,
            ),
        )
        assertFailsWith<UnsupportedEngineFeatureError> { exprRenderer.renderSqlExpr(expr) }
    }

    @Test
    fun `cast to timestamp uses DATETIME`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlCastToTimestampExpression.create(arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("CAST(events.created_at AS DATETIME)", result.sql)
    }

    @Test
    fun `date trunc reverses argument order from ANSI`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlDateTruncExpression.create(timeGranularity = TimeGranularity.DAY, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("DATETIME_TRUNC(events.created_at, day)", result.sql)
    }

    @Test
    fun `date trunc with WEEK uses isoweek prefix`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlDateTruncExpression.create(timeGranularity = TimeGranularity.WEEK, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("DATETIME_TRUNC(events.created_at, isoweek)", result.sql)
    }

    @Test
    fun `extract DOW renormalises to ISO week ordering`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOW, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "IF(EXTRACT(dayofweek FROM events.created_at) = 1, 7, EXTRACT(dayofweek FROM events.created_at) - 1)",
            result.sql,
        )
    }

    @Test
    fun `extract DOY uses dayofyear`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOY, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(dayofyear FROM events.created_at)", result.sql)
    }

    @Test
    fun `subtract time interval uses DATE_SUB with DATETIME cast`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlSubtractTimeIntervalExpression.create(arg = col, count = 7, granularity = TimeGranularity.DAY)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "DATE_SUB(CAST(events.created_at AS DATETIME), INTERVAL 7 day)",
            result.sql,
        )
    }

    @Test
    fun `add time uses DATE_ADD with DATETIME cast`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlAddTimeExpression.create(arg = col, countExpr = SqlIntegerExpression.create(3), granularity = TimeGranularity.MONTH)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "DATE_ADD(CAST(events.created_at AS DATETIME), INTERVAL 3 month)",
            result.sql,
        )
    }

    @Test
    fun `generate uuid uses GENERATE_UUID`() {
        val result = exprRenderer.renderSqlExpr(SqlGenerateUuidExpression.create())
        assertEquals("GENERATE_UUID()", result.sql)
    }

    @Test
    fun `doubleToLimitedFraction reduces 0_25 to 1 over 4`() {
        val (n, d) = BigQuerySqlExpressionRenderer.doubleToLimitedFraction(0.25, BigQuerySqlExpressionRenderer.LIMIT_DENOMINATOR_DEFAULT)
        assertEquals(1L to 4L, n to d)
    }

    @Test
    fun `doubleToLimitedFraction handles zero`() {
        val (n, d) = BigQuerySqlExpressionRenderer.doubleToLimitedFraction(0.0, BigQuerySqlExpressionRenderer.LIMIT_DENOMINATOR_DEFAULT)
        assertEquals(0L to 1L, n to d)
    }
}
