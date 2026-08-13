package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.common.errors.SemanticManifestConfigurationError
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
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
    fun `metric time group by still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "bookings",
                    groupByNames = listOf("METRIC_TIME__DAY"),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    @Test
    fun `time range still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "bookings",
                    groupByNames = emptyList(),
                    timeConstraintStart = "2026-01-01",
                    timeConstraintEnd = "2026-01-31",
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    @Test
    fun `cumulative metric still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "revenue_all_time",
                    groupByNames = emptyList(),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    @Test
    fun `time offset metric still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "bookings_5_day_lag",
                    groupByNames = emptyList(),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    @Test
    fun `conversion metric still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "visit_buy_conversion_rate",
                    groupByNames = emptyList(),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    @Test
    fun `join to time spine metric still requires a configured time spine`() {
        val exception = assertFailsWith<SemanticManifestConfigurationError> {
            engineWithoutTimeSpine().explain(
                request(
                    metricName = "bookings_join_to_time_spine",
                    groupByNames = emptyList(),
                    timeConstraintStart = null,
                    timeConstraintEnd = null,
                ),
            )
        }

        assertContains(exception.message.orEmpty(), "time spine", ignoreCase = true)
    }

    private fun engineWithoutTimeSpine(): MetricFlowEngine {
        val repoRoot = System.getProperty("metricflow.repoRoot")
        val manifestFile = File(repoRoot, "corpus/manifests/simple_manifest.json")
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            manifestFile.readText(),
        )
        return MetricFlowEngine(
            manifest.copy(
                projectConfiguration = manifest.projectConfiguration.copy(
                    timeSpineTableConfigurations = emptyList(),
                    timeSpines = emptyList(),
                ),
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
