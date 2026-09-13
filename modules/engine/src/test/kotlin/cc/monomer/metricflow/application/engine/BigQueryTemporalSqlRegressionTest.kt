package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlExpressionRenderer

class BigQueryTemporalSqlRegressionTest {
    @Test
    fun `temporal operators compose through engine plans without coercing opaque source types`() {
        val output = File(System.getProperty("metricflow.repoRoot"), "build/bigquery-temporal-sql").apply { mkdirs() }
        for (sourceType in listOf("DATE", "DATETIME", "TIMESTAMP")) {
            val manifest = manifest("simple_manifest", sourceType)
            val scenarios = linkedMapOf(
                "range" to request(listOf("bookings")),
                "no_range" to request(listOf("bookings"), range = false),
                "typed_date_filter" to request(listOf("bookings"), filters = listOf("{{ TimeDimension('metric_time', 'day') }} >= DATE '2026-08-03'")),
                "empty" to request(listOf("bookings"), filters = listOf("{{ TimeDimension('metric_time', 'day') }} >= DATE '2026-09-01'")),
                "week" to request(listOf("bookings"), listOf("metric_time__week")),
                "month" to request(listOf("bookings"), listOf("metric_time__month")),
                "filter" to request(listOf("bookings"), filters = listOf("{{ TimeDimension('metric_time', 'day') }} >= '2026-08-01'")),
                "spine" to request(listOf("bookings_fill_nulls_with_0")),
                "entity_join" to request(listOf("bookings"), listOf("metric_time__day", "listing__country_latest")),
                "ratio" to request(listOf("bookings_per_view")),
                "derived" to request(listOf("booking_fees_per_booker")),
                "cumulative" to request(listOf("bookings_all_time")),
                "conversion" to request(listOf("visit_buy_conversion_rate_7days")),
                "multi_metric" to request(listOf("bookings", "listings", "views")),
            )
            if (sourceType == "TIMESTAMP") scenarios["utc_timestamp_filter"] = request(
                listOf("bookings"),
                filters = listOf("{{ TimeDimension('metric_time', 'day') }} >= TIMESTAMP '2026-08-03 00:00:00+00'"),
            )
            for ((name, request) in scenarios) {
                val sql = engine(manifest).explain(request).sql
                val baseline = engine(manifest, baselineRenderer).explain(request).sql
                assertEquals(baseline.replace("DATETIME_TRUNC(", "DATE_TRUNC("), sql)
                File(output, "${sourceType.lowercase()}_$name.baseline.sql").writeText(bindInlineSources(baseline))
                assertFalse(sql.contains("DATETIME_TRUNC("), "$sourceType/$name")
                assertContains(sql, "DATE_TRUNC(")
                if (sourceType != "DATE") assertContains(sql, "CAST(")
                if (name == "week") assertContains(sql, "isoweek")
                if (name == "spine" || name == "cumulative") assertContains(sql, "mf_time_spine")
                if (name == "entity_join" || name == "ratio" || name == "conversion") assertContains(sql, "JOIN")
                File(output, "${sourceType.lowercase()}_$name.sql").writeText(bindInlineSources(sql))
                File(output, "${sourceType.lowercase()}_$name.engine.sql").writeText(sql)
                if (sourceType == "TIMESTAMP" && name in listOf("spine", "cumulative")) {
                    File(output, "timestamp_${name}_aligned.sql").writeText(bindInlineSources(sql, timestampSpine = true))
                    File(output, "timestamp_${name}_aligned.baseline.sql").writeText(bindInlineSources(baseline, timestampSpine = true))
                }
            }
            val partitionManifest = manifest("partitioned_multi_hop_join_manifest", sourceType)
            val partitionRequest = request(listOf("txn_count"), listOf("account_id__customer_id__customer_name"), range = false)
            val partitionSql = engine(partitionManifest).explain(partitionRequest).sql
            val partitionBaseline = engine(partitionManifest, baselineRenderer).explain(partitionRequest).sql
            assertEquals(partitionBaseline.replace("DATETIME_TRUNC(", "DATE_TRUNC("), partitionSql)
            File(output, "${sourceType.lowercase()}_partition_join.baseline.sql").writeText(bindInlineSources(partitionBaseline))
            assertContains(partitionSql, "ds_partitioned")
            assertContains(partitionSql, "JOIN")
            assertFalse(partitionSql.contains("DATETIME_TRUNC("))
            File(output, "${sourceType.lowercase()}_partition_join.sql").writeText(bindInlineSources(partitionSql))
            File(output, "${sourceType.lowercase()}_partition_join.engine.sql").writeText(partitionSql)
        }
    }

    private val baselineRenderer = object : BigQuerySqlPlanRenderer() {
        override val exprRenderer = object : BigQuerySqlExpressionRenderer() {
            override fun visitDateTruncExpr(node: SqlDateTruncExpression): SqlExpressionRenderResult {
                val argRendered = renderSqlExpr(node.arg)
                val prefix = if (node.timeGranularity == TimeGranularity.WEEK) "iso" else ""
                return SqlExpressionRenderResult(
                    sql = "DATETIME_TRUNC(${argRendered.sql}, $prefix${node.timeGranularity.value})",
                    bindParameterSet = argRendered.bindParameterSet,
                )
            }
        }
    }

    private fun manifest(name: String, sourceType: String): SemanticManifest {
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            File(System.getProperty("metricflow.repoRoot"), "corpus/manifests/$name.json").readText(),
        )
        return manifest.copy(semanticModels = manifest.semanticModels.map { model ->
            model.copy(dimensions = model.dimensions.map { dimension ->
                if (dimension.type != DimensionType.TIME || sourceType == "DATE") dimension
                else dimension.copy(expr = "CAST(${dimension.expr ?: dimension.name} AS $sourceType)")
            })
        })
    }

    private fun engine(manifest: SemanticManifest, renderer: BigQuerySqlPlanRenderer = BigQuerySqlPlanRenderer()) = MetricFlowEngine(
        semanticManifest = manifest,
        sqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
            SqlPlanRendererRegistration(SqlEngine.BIGQUERY, renderer),
        ),
    )

    private fun request(
        metrics: List<String>,
        groups: List<String> = listOf("metric_time__day"),
        filters: List<String> = emptyList(),
        range: Boolean = true,
    ) = MetricFlowExplainRequest(
        metricNames = metrics, groupByNames = groups, whereConstraints = filters,
        orderByNames = emptyList(), limit = null,
        timeConstraintStart = if (range) "2026-08-01" else null,
        timeConstraintEnd = if (range) "2026-08-05" else null,
        savedQueryName = null, minMaxOnly = false, applyGroupBy = true,
        orderOutputColumnsByInputOrder = false, dialect = SqlEngine.BIGQUERY,
    )

    // Only fixture relations are replaced; every engine-generated expression and operator is preserved.
    private fun bindInlineSources(sql: String, timestampSpine: Boolean = false): String {
        val source = """(
            SELECT d AS ds, d AS ds_partitioned, d AS paid_at, d AS created_at,
              d AS ds_month, d AS last_profile_edit_ts, d AS bio_added_ts, d AS last_login_ts, d AS archived_at,
              i AS listing_id, i AS guest_id, i AS host_id, i AS user_id, i AS session_id,
              i AS account_id, i AS customer_id, i AS txn_count,
              'Customer' AS customer_name, 'US' AS country, 'CA' AS home_state,
              TRUE AS is_instant, FALSE AS is_lux, 2 AS capacity,
              100 * i AS booking_value, 10 * i AS revenue, CAST(NULL AS INT64) AS referrer_id
            FROM UNNEST([DATE '2026-08-01', DATE '2026-08-03', DATE '2026-08-05']) AS d WITH OFFSET AS i
        )""".trimIndent()
        val spineColumn = if (timestampSpine) "CAST(ds AS TIMESTAMP) AS ds" else "ds"
        val spine = "(SELECT $spineColumn FROM UNNEST(GENERATE_DATE_ARRAY(DATE '2026-08-01', DATE '2026-08-05')) AS ds)"
        return Regex("`?mf_corpus_2026_05_11_static\\.([a-zA-Z0-9_]+)`?").replace(sql) { match ->
            when (match.groupValues[1]) {
                "mf_time_spine" -> spine
                "account_month_txns", "bridge_table", "customer_table" -> source
                    .replace("i AS account_id, i AS customer_id", "7 AS account_id, 9 AS customer_id")
                else -> source
            }
        }
    }
}
