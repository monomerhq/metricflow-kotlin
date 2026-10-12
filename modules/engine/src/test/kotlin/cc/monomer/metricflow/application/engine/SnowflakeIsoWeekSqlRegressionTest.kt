package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlExpressionRenderer
import cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Snowflake week buckets must not depend on the account or session `WEEK_START` parameter.
 *
 * Every engine path that truncates to a week (group-by, time spine join, offset window,
 * grain-to-date, offset-to-grain, and the Monday-adjusted time constraint) is compared with the
 * prior `DATE_TRUNC('week', x)` renderer. The only permitted difference is that each week
 * truncation becomes the ISO Monday expression over the same operand. The generated SQL and a
 * literal probe for manual warehouse checks are written under `build/snowflake-iso-week-sql/`.
 */
class SnowflakeIsoWeekSqlRegressionTest {
    @Test
    fun `week paths render the ISO Monday and change nothing else`() {
        val output = File(System.getProperty("metricflow.repoRoot"), "build/snowflake-iso-week-sql").apply { mkdirs() }
        for (sourceType in listOf("DATE", "TIMESTAMP_NTZ", "TIMESTAMP_LTZ", "TIMESTAMP_TZ")) {
            val manifest = manifest(sourceType)
            val scenarios = linkedMapOf(
                "metric_time_week" to request(listOf("bookings"), listOf("metric_time__week")),
                "listing_dimension_week" to request(listOf("bookings"), listOf("listing__ds__week")),
                "time_spine_week" to request(listOf("bookings_fill_nulls_with_0"), listOf("metric_time__week")),
                "offset_window_week" to request(
                    listOf("booking_fees_last_week_per_booker_this_week"),
                    listOf("metric_time__week"),
                ),
                "grain_to_date_week" to request(listOf("revenue_wtd"), listOf("metric_time__day")),
                "offset_to_grain_week" to request(listOf("bookings_at_start_of_week"), listOf("metric_time__day")),
            )
            for ((name, request) in scenarios) {
                val sql = engine(manifest, SnowflakeSqlPlanRenderer()).explain(request).sql
                val baseline = engine(manifest, weekStartDependentRenderer).explain(request).sql
                assertContains(baseline, "DATE_TRUNC('week', ", message = "$sourceType/$name")
                assertEquals(replaceWeekTruncations(baseline), sql, "$sourceType/$name")
                assertFalse(sql.contains("DATE_TRUNC('week'"), "$sourceType/$name")
                assertContains(sql, "DAYOFWEEKISO(")
                if (sourceType != "DATE") assertContains(sql, "AS $sourceType)")
                if (name == "time_spine_week" || name == "grain_to_date_week") assertContains(sql, "mf_time_spine")
                if (name == "metric_time_week") assertContains(sql, "'2026-08-03'")
                File(output, "${sourceType.lowercase()}_$name.sql").writeText(sql)
            }
        }
        File(output, "probe.sql").writeText(probeSql())
    }

    private val weekStartDependentRenderer = object : SnowflakeSqlPlanRenderer() {
        override val exprRenderer = object : SnowflakeSqlExpressionRenderer() {
            override fun visitDateTruncExpr(node: SqlDateTruncExpression): SqlExpressionRenderResult {
                val argRendered = renderSqlExpr(node.arg)
                return SqlExpressionRenderResult(
                    sql = "DATE_TRUNC('${node.timeGranularity.value}', ${argRendered.sql})",
                    bindParameterSet = argRendered.bindParameterSet,
                )
            }
        }
    }

    /** Rewrites each `DATE_TRUNC('week', operand)` call, keeping its balanced-parenthesis operand verbatim. */
    private fun replaceWeekTruncations(sql: String): String {
        val weekTruncationPrefix = "DATE_TRUNC('week', "
        val rewritten = StringBuilder()
        var cursor = 0
        while (true) {
            val start = sql.indexOf(weekTruncationPrefix, cursor)
            if (start < 0) break
            val operandStart = start + weekTruncationPrefix.length
            var depth = 1
            var operandEnd = operandStart
            while (depth > 0) {
                when (sql[operandEnd]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                operandEnd++
            }
            val operand = replaceWeekTruncations(sql.substring(operandStart, operandEnd - 1))
            rewritten.append(sql, cursor, start)
            rewritten.append("DATEADD(day, 1 - DAYOFWEEKISO($operand), DATE_TRUNC('day', $operand))")
            cursor = operandEnd
        }
        return rewritten.append(sql.substring(cursor)).toString()
    }

    private fun manifest(sourceType: String): SemanticManifest {
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            File(System.getProperty("metricflow.repoRoot"), "corpus/manifests/simple_manifest.json").readText(),
        )
        val metricsByName = manifest.metrics.associateBy(Metric::name)
        val revenueMonthToDate = metricsByName.getValue("revenue_mtd")
        val bookingsAtStartOfMonth = metricsByName.getValue("bookings_at_start_of_month")
        val revenueWeekToDate = revenueMonthToDate.copy(
            name = "revenue_wtd",
            typeParams = revenueMonthToDate.typeParams.copy(
                cumulativeTypeParams = revenueMonthToDate.typeParams.cumulativeTypeParams!!.copy(
                    grainToDate = TimeGranularity.WEEK.value,
                ),
            ),
        )
        val bookingsAtStartOfWeek = bookingsAtStartOfMonth.copy(
            name = "bookings_at_start_of_week",
            typeParams = bookingsAtStartOfMonth.typeParams.copy(
                metrics = bookingsAtStartOfMonth.typeParams.metrics!!.map { input ->
                    input.copy(offsetToGrain = TimeGranularity.WEEK.value)
                },
            ),
        )
        return manifest.copy(
            metrics = manifest.metrics + listOf(revenueWeekToDate, bookingsAtStartOfWeek),
            semanticModels = manifest.semanticModels.map { model ->
                model.copy(dimensions = model.dimensions.map { dimension ->
                    if (dimension.type != DimensionType.TIME || sourceType == "DATE") dimension
                    else dimension.copy(expr = "CAST(${dimension.expr ?: dimension.name} AS $sourceType)")
                })
            },
        )
    }

    private fun engine(manifest: SemanticManifest, renderer: SnowflakeSqlPlanRenderer) = MetricFlowEngine(
        semanticManifest = manifest,
        sqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
            SqlPlanRendererRegistration(SqlEngine.SNOWFLAKE, renderer),
        ),
    )

    private fun request(metrics: List<String>, groups: List<String>) = MetricFlowExplainRequest(
        metricNames = metrics, groupByNames = groups, whereConstraints = emptyList(),
        orderByNames = emptyList(), limit = null,
        timeConstraintStart = "2026-08-05", timeConstraintEnd = "2026-08-12",
        savedQueryName = null, minMaxOnly = false, applyGroupBy = true,
        orderOutputColumnsByInputOrder = false, dialect = SqlEngine.SNOWFLAKE,
    )

    /**
     * A self-contained Snowflake script for manual checks: under several `WEEK_START` values, every
     * source type truncates Sunday 2026-08-09 to Monday 2026-08-03 and keeps its input type.
     */
    private fun probeSql(): String {
        val weekStart = SnowflakeSqlPlanRenderer.EXPR_RENDERER.renderSqlExpr(
            SqlDateTruncExpression.create(
                TimeGranularity.WEEK,
                SqlColumnReferenceExpression.fromColumnReference("probe", "source_value"),
            ),
        ).sql
        val probes = listOf("DATE", "TIMESTAMP_NTZ", "TIMESTAMP_LTZ", "TIMESTAMP_TZ").joinToString("\nUNION ALL\n") { sourceType ->
            """
            SELECT
              '$sourceType' AS source_type
              , CAST(probe.source_value AS VARCHAR) AS source_value
              , CAST($weekStart AS VARCHAR) AS iso_week_start
              , CAST($weekStart AS DATE) = probe.expected_monday AS is_expected_monday
              , SYSTEM${'$'}TYPEOF(probe.source_value) AS source_type_of
              , SYSTEM${'$'}TYPEOF($weekStart) AS iso_week_start_type_of
            FROM (
              SELECT CAST(CAST(column1 AS TIMESTAMP_NTZ) AS $sourceType) AS source_value, CAST(column2 AS DATE) AS expected_monday
              FROM VALUES
                ('2026-08-03 00:00:00', '2026-08-03')
                , ('2026-08-05 12:34:56', '2026-08-03')
                , ('2026-08-09 23:30:00', '2026-08-03')
                , ('2026-08-10 00:00:00', '2026-08-10')
            ) probe
            """.trimIndent()
        }
        return listOf(0, 1, 3, 7).joinToString("\n\n") { weekStartParameter ->
            "ALTER SESSION SET WEEK_START = $weekStartParameter, WEEK_OF_YEAR_POLICY = 1;\n$probes\nORDER BY source_type, source_value;"
        } + "\n"
    }
}
