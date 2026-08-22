package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AtemporalQueryWithoutTimeSpineTest {

    @Test
    fun `simple metric renders without a time spine when the query is atemporal`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `ratio metric renders without a time spine when the query is atemporal`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings_per_booker",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `nested derived metric renders through its derived input`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "booking_value_sub_instant_add_10",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertContains(result.sql, "booking_value_sub_instant")
        assertContains(result.sql, "booking_value_sub_instant_add_10")
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `categorical grouping renders without a time spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings",
                groupByNames = listOf("booking__is_instant"),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertContains(result.sql, "is_instant")
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `model owned time grouping renders without a time spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings",
                groupByNames = listOf("booking__ds__day"),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertContains(result.sql, "booking__ds__day")
        assertContains(result.sql, "GROUP BY")
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `simple metric time group by uses the model time column without a configured spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings",
                groupByNames = listOf("METRIC_TIME__DAY"),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertContains(result.sql, "GROUP BY")
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `simple metric time range uses the model time column without a configured spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings",
                groupByNames = emptyList(),
                timeConstraintStart = "2026-01-01",
                timeConstraintEnd = "2026-01-31",
            ),
        )

        assertFalse(result.sql.isBlank())
        assertContains(result.sql, "WHERE")
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `all-time cumulative metric does not require a physical spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "revenue_all_time",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `time offset metric without metric time is rejected during query resolution`() {
        val exception = assertFailsWith<IllegalArgumentException> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "bookings_5_day_lag",
                    groupByNames = emptyList(),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "OffsetMetricRequiresMetricTimeIssue")
    }

    @Test
    fun `atemporal conversion metric does not require a physical spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "visit_buy_conversion_rate",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertFalse(result.sql.contains("time_spine", ignoreCase = true))
    }

    @Test
    fun `atemporal physical time-spine join metric does not join a physical spine`() {
        val result = engineWithoutTimeSpine().explain(
            request(
                metricName = "bookings_join_to_time_spine",
                groupByNames = emptyList(),
                timeConstraintStart = null,
                timeConstraintEnd = null,
            ),
        )

        assertFalse(result.sql.isBlank())
        assertFalse(Regex("\\bJOIN\\b", RegexOption.IGNORE_CASE).containsMatchIn(result.sql), result.sql)
    }

    private fun engineWithoutTimeSpine(): MetricFlowEngine {
        val repoRoot = System.getProperty("metricflow.repoRoot")
        val manifestFile = File(repoRoot, "corpus/manifests/simple_manifest.json")
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            manifestFile.readText(),
        )
        return MetricFlowEngine(
            semanticManifest = manifest.copy(
                projectConfiguration = manifest.projectConfiguration.copy(
                    timeSpineTableConfigurations = emptyList(),
                    timeSpines = emptyList(),
                ),
            ),
            sqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
                SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer()),
            ),
        )
    }

    private fun request(
        metricName: String,
        groupByNames: List<String>,
        timeConstraintStart: String?,
        timeConstraintEnd: String?,
    ): MetricFlowExplainRequest = MetricFlowExplainRequest(
        metricNames = listOf(metricName),
        groupByNames = groupByNames,
        whereConstraints = emptyList(),
        orderByNames = emptyList(),
        limit = null,
        timeConstraintStart = timeConstraintStart,
        timeConstraintEnd = timeConstraintEnd,
        savedQueryName = null,
        minMaxOnly = false,
        applyGroupBy = true,
        orderOutputColumnsByInputOrder = false,
        dialect = SqlEngine.BIGQUERY,
    )
}
