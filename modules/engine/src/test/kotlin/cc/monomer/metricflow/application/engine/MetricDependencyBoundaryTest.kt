package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.common.errors.MetricDefinitionDependencyError
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class MetricDependencyBoundaryTest {

    @Test
    fun `all metric-scoped engine entries reject cycles before semantic graph construction`() {
        val engine = MetricFlowEngine(
            semanticManifest = manifestWithMetricCycle(),
            sqlPlanRendererRegistry = SqlPlanRendererRegistry.of(
                SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer()),
            ),
        )
        val operations = listOf<() -> Unit>(
            { engine.listMetrics(includeDimensions = true) },
            { engine.listDimensions(listOf("metric_a"), GroupByOrderByAttribute.DUNDER_NAME) },
            { engine.entitiesForMetrics(listOf("metric_a")) },
            {
                engine.listGroupBys(
                    metricNames = listOf("metric_a"),
                    includeDerivedTimeGranularities = false,
                    orderBy = GroupByOrderByAttribute.DUNDER_NAME,
                )
            },
            { engine.explain(explainRequest()) },
            {
                engine.explainGetDimensionValues(
                    ExplainGetDimensionValuesRequest(
                        metricNames = listOf("metric_a"),
                        getGroupByValues = "metric_time",
                        timeConstraintStart = null,
                        timeConstraintEnd = null,
                        minMaxOnly = false,
                        dialect = SqlEngine.BIGQUERY,
                    ),
                )
            },
        )

        operations.forEach { operation ->
            val error = assertFailsWith<MetricDefinitionDependencyError> { operation() }
            assertContains(error.message.orEmpty(), "metric_a -> metric_b -> metric_a")
        }
    }

    private fun manifestWithMetricCycle(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot")
        val manifestFile = File(repoRoot, "corpus/manifests/simple_manifest.json")
        val manifest = ManifestJson.decodeFromString(
            SemanticManifest.serializer(),
            manifestFile.readText(),
        )
        return manifest.copy(
            metrics = manifest.metrics + listOf(
                derivedMetric(name = "metric_a", inputName = "metric_b"),
                derivedMetric(name = "metric_b", inputName = "metric_a"),
            ),
        )
    }

    private fun derivedMetric(name: String, inputName: String): Metric = Metric(
        name = name,
        type = MetricType.DERIVED,
        typeParams = MetricTypeParams(
            expr = inputName,
            metrics = listOf(MetricInput(name = inputName)),
        ),
    )

    private fun explainRequest(): MetricFlowExplainRequest = MetricFlowExplainRequest(
        metricNames = listOf("metric_a"),
        groupByNames = emptyList(),
        whereConstraints = emptyList(),
        orderByNames = emptyList(),
        limit = null,
        timeConstraintStart = null,
        timeConstraintEnd = null,
        savedQueryName = null,
        minMaxOnly = false,
        applyGroupBy = true,
        orderOutputColumnsByInputOrder = false,
        dialect = SqlEngine.BIGQUERY,
    )
}
