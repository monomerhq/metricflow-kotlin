package cc.monomer.metricflow.infrastructure.sql.render.snowflake

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameter
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterValue
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpressionArgument
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SnowflakeSqlPlanRendererTest {

    private val planRenderer = SnowflakeSqlPlanRenderer()
    private val exprRenderer = planRenderer.exprRenderer

    @Test
    fun `supports continuous discrete and approximate continuous percentile`() {
        assertEquals(
            setOf(
                SqlPercentileFunctionType.CONTINUOUS,
                SqlPercentileFunctionType.DISCRETE,
                SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
            exprRenderer.supportedPercentileFunctionTypes,
        )
    }

    @Test
    fun `generate uuid uses UUID_STRING`() {
        val result = exprRenderer.renderSqlExpr(SqlGenerateUuidExpression.create())
        assertEquals("UUID_STRING()", result.sql)
    }

    @Test
    fun `EXTRACT DOW uses dayofweekiso`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val expr = SqlExtractExpression.create(datePart = DatePart.DOW, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("EXTRACT(dayofweekiso FROM events.created_at)", result.sql)
    }

    @Test
    fun `EXTRACT renders every date part without week session parameters`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        val renderedDateParts = DatePart.entries.associateWith { datePart ->
            exprRenderer.renderSqlExpr(SqlExtractExpression.create(datePart = datePart, arg = col)).sql
        }
        assertEquals(
            mapOf(
                DatePart.YEAR to "EXTRACT(year FROM events.created_at)",
                DatePart.QUARTER to "EXTRACT(quarter FROM events.created_at)",
                DatePart.MONTH to "EXTRACT(month FROM events.created_at)",
                DatePart.DAY to "EXTRACT(day FROM events.created_at)",
                DatePart.DOW to "EXTRACT(dayofweekiso FROM events.created_at)",
                DatePart.DOY to "EXTRACT(doy FROM events.created_at)",
            ),
            renderedDateParts,
        )
    }

    @Test
    fun `week truncation of a DATE column starts on the ISO Monday`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("bookings_source", "ds")
        val expr = SqlDateTruncExpression.create(timeGranularity = TimeGranularity.WEEK, arg = col)
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals(
            "DATEADD(day, 1 - DAYOFWEEKISO(bookings_source.ds), DATE_TRUNC('day', bookings_source.ds))",
            result.sql,
        )
        assertEquals(SqlBindParameterSet.EMPTY, result.bindParameterSet)
    }

    @Test
    fun `week truncation preserves DATE and TIMESTAMP operands without adding a cast`() {
        val parameters = SqlBindParameterSet(
            listOf(SqlBindParameter("source", SqlBindParameterValue.StringValue("2026-08-09 23:30:00"))),
        )
        for (sourceType in listOf("DATE", "TIMESTAMP_NTZ", "TIMESTAMP_LTZ", "TIMESTAMP_TZ")) {
            val sourceSql = "CAST(:source AS $sourceType)"
            val source = SqlStringExpression.create(sourceSql, parameters, false, emptyList())
            val result = exprRenderer.renderSqlExpr(
                SqlDateTruncExpression.create(timeGranularity = TimeGranularity.WEEK, arg = source),
            )
            assertEquals(
                "DATEADD(day, 1 - DAYOFWEEKISO($sourceSql), DATE_TRUNC('day', $sourceSql))",
                result.sql,
            )
            assertEquals(parameters, result.bindParameterSet)
        }
    }

    @Test
    fun `non-week truncation keeps the default DATE_TRUNC rendering`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "created_at")
        for (granularity in TimeGranularity.entries.filter { it != TimeGranularity.WEEK }) {
            val result = exprRenderer.renderSqlExpr(SqlDateTruncExpression.create(granularity, col))
            assertEquals("DATE_TRUNC('${granularity.value}', events.created_at)", result.sql)
        }
    }

    @Test
    fun `week offsets add whole weeks without week session parameters`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("time_spine", "ds")
        val subtracted = exprRenderer.renderSqlExpr(
            SqlSubtractTimeIntervalExpression.create(arg = col, count = 1, granularity = TimeGranularity.WEEK),
        )
        assertEquals("DATEADD(week, -1, time_spine.ds)", subtracted.sql)
        val added = exprRenderer.renderSqlExpr(
            SqlAddTimeExpression.create(
                arg = col,
                countExpr = SqlIntegerExpression.create(2),
                granularity = TimeGranularity.WEEK,
            ),
        )
        assertEquals("DATEADD(week, 2, time_spine.ds)", added.sql)
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
    fun `percentile discrete uses PERCENTILE_DISC WITHIN GROUP`() {
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
    fun `percentile approximate continuous uses APPROX_PERCENTILE`() {
        val col = SqlColumnReferenceExpression.fromColumnReference("events", "value")
        val expr = SqlPercentileExpression.create(
            orderByArg = col,
            percentileArgs = SqlPercentileExpressionArgument(
                percentile = 0.5,
                functionType = SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
            ),
        )
        val result = exprRenderer.renderSqlExpr(expr)
        assertEquals("APPROX_PERCENTILE(events.value, 0.5)", result.sql)
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
    fun `plan rendering is ANSI for plain SELECT`() {
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
}
