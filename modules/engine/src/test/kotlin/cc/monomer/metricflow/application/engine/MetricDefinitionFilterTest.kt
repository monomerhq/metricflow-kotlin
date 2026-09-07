package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilter
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetricDefinitionFilterTest {
    @Test
    fun `simple definition filter is applied before aggregation without projecting its dimension`() {
        val result = explain(listOf("filtered"))
        assertContains(result.sql, "is_instant = true")
        assertContains(result.sql, "WHERE")
        assertFalse(result.sql.contains("GROUP BY"), result.sql)
    }

    @Test
    fun `ratio does not merge filtered and unfiltered inputs on the same model`() {
        val sql = explain(listOf("share")).sql
        assertContains(sql, "is_instant = true")
        assertContains(sql, "CROSS JOIN")
        assertEquals(2, Regex("SUM\\(").findAll(sql).count(), sql)
        assertContains(sql, "filtered")
        assertContains(sql, "booking_value")
    }

    @Test
    fun `nested derived input filters keep aliases and combine with metric and query filters`() {
        val sql = explain(listOf("nested"), listOf("{{ Dimension('booking__is_instant') }} IS NOT NULL")).sql
        assertContains(sql, "is_instant = true")
        assertContains(sql, "is_instant = false")
        assertContains(sql, "is_instant IS NOT NULL")
        assertContains(sql, "left_value")
        assertContains(sql, "right_value")
    }

    @Test
    fun `multiple metrics preserve independently filtered branches`() {
        val sql = explain(listOf("filtered", "booking_value")).sql
        assertEquals(1, Regex("is_instant = true").findAll(sql).count(), sql)
        assertContains(sql, "CROSS JOIN")
        assertEquals(2, Regex("SUM\\(").findAll(sql).count(), sql)
    }

    @Test
    fun `filter-only joined dimension is joined without becoming a grouping key`() {
        val result = explain(listOf("joined"))
        assertContains(result.sql, "country_latest = 'KR'")
        assertContains(result.sql, "JOIN")
        assertFalse(result.sql.contains("GROUP BY"), result.sql)
        assertTrue(result.usedRelations.any { it.tableName == "dim_listings_latest" }, result.usedRelations.toString())
        assertTrue(result.queriedSemanticModels.any { it.semanticModelName == "listings_latest" }, result.queriedSemanticModels.toString())
    }

    @Test
    fun `unfiltered metric stays unfiltered`() {
        assertFalse(explain(listOf("booking_value")).sql.contains("WHERE"))
    }

    @Test
    fun `ratio input filters preserve numerator and denominator aliases`() {
        val sql = explain(listOf("input_share")).sql
        assertContains(sql, "is_instant = true")
        assertContains(sql, "is_instant = false")
        assertContains(sql, "numerator_value")
        assertContains(sql, "denominator_value")
        assertContains(sql, "CROSS JOIN")
        assertRatioBindings(sql, "numerator_value", "denominator_value")
    }

    @Test
    fun `same metric ratio inputs with different filters work without aliases`() {
        val sql = explain(listOf("unaliased_share")).sql
        assertContains(sql, "is_instant = true")
        assertContains(sql, "is_instant = false")
        val ratioColumns = Regex("MAX\\([^)]*\\.([^).]+)\\)")
            .findAll(sql).map { it.groupValues[1] }.toSet()
        assertEquals(2, ratioColumns.size, sql)
        assertRatioBindings(sql, "__metricflow_ratio_numerator", "__metricflow_ratio_denominator")
        assertTrue(Regex("AS __metricflow_ratio_numerator[\\s\\S]*?WHERE booking__is_instant = true").containsMatchIn(sql), sql)
        assertTrue(Regex("AS __metricflow_ratio_denominator[\\s\\S]*?WHERE booking__is_instant = false").containsMatchIn(sql), sql)
    }

    @Test
    fun `same unfiltered ratio input remains valid without aliases`() {
        val sql = explain(listOf("self_share")).sql
        assertFalse(sql.contains("WHERE"), sql)
        assertEquals(2, Regex("SUM\\(booking_value\\)").findAll(sql).count(), sql)
        assertContains(sql, "AS self_share")
    }

    @Test
    fun `query-only joined filter contributes model provenance`() {
        val result = explain(listOf("booking_value"), listOf("{{ Dimension('listing__country_latest') }} = 'KR'"))
        assertContains(result.sql, "country_latest = 'KR'")
        assertTrue(result.queriedSemanticModels.any { it.semanticModelName == "listings_latest" })
    }

    @Test
    fun `unavailable metric filter dimension fails instead of dropping the predicate`() {
        assertFailsWith<IllegalArgumentException> { explain(listOf("invalid_filter")) }
    }

    @Test
    fun `unsupported conversion input filter fails instead of returning unfiltered SQL`() {
        assertFailsWith<NotImplementedError> { explain(listOf("filtered_conversion")) }
    }

    private fun explain(names: List<String>, filters: List<String> = emptyList()): MetricFlowExplainResult {
        val manifest = ManifestJson.decodeFromString(SemanticManifest.serializer(),
            File(System.getProperty("metricflow.repoRoot"), "corpus/manifests/simple_manifest.json").readText())
        val base = manifest.metrics.single { it.name == "booking_value" }
        val filtered = base.copy(name = "filtered", filter = filter("{{ Dimension('booking__is_instant') }} = true"))
        val joined = base.copy(name = "joined", filter = filter("{{ Dimension('listing__country_latest') }} = 'KR'"))
        val share = Metric(name = "share", type = MetricType.RATIO, typeParams = MetricTypeParams(
            numerator = MetricInput("filtered"), denominator = MetricInput("booking_value")))
        val difference = Metric(name = "difference", type = MetricType.DERIVED, typeParams = MetricTypeParams(
            expr = "left_value - right_value", metrics = listOf(
                MetricInput("booking_value", filter = filter("{{ Dimension('booking__is_instant') }} = true"), alias = "left_value"),
                MetricInput("booking_value", filter = filter("{{ Dimension('booking__is_instant') }} = false"), alias = "right_value"))))
        val nested = Metric(name = "nested", type = MetricType.DERIVED,
            filter = filter("{{ Dimension('booking__is_instant') }} IS NOT NULL"),
            typeParams = MetricTypeParams(expr = "inner_value + 10", metrics = listOf(MetricInput("difference", alias = "inner_value"))))
        val inputShare = Metric(name = "input_share", type = MetricType.RATIO, typeParams = MetricTypeParams(
            numerator = MetricInput("booking_value", filter = filter("{{ Dimension('booking__is_instant') }} = true"), alias = "numerator_value"),
            denominator = MetricInput("booking_value", filter = filter("{{ Dimension('booking__is_instant') }} = false"), alias = "denominator_value")))
        val unaliasedShare = inputShare.copy(name = "unaliased_share", typeParams = inputShare.typeParams.copy(
            numerator = requireNotNull(inputShare.typeParams.numerator).copy(alias = null),
            denominator = requireNotNull(inputShare.typeParams.denominator).copy(alias = null)))
        val selfShare = unaliasedShare.copy(name = "self_share", typeParams = unaliasedShare.typeParams.copy(
            numerator = MetricInput("booking_value"), denominator = MetricInput("booking_value")))
        val invalidFilter = base.copy(name = "invalid_filter", filter = filter("{{ Dimension('booking__missing') }} = true"))
        val conversion = manifest.metrics.single { it.name == "visit_buy_conversion_rate" }
        val conversionParams = requireNotNull(conversion.typeParams.conversionTypeParams)
        val filteredConversion = conversion.copy(name = "filtered_conversion", typeParams = conversion.typeParams.copy(
            conversionTypeParams = conversionParams.copy(baseMetric = requireNotNull(conversionParams.baseMetric).copy(filter = filter("1 = 0")))))
        return MetricFlowEngine(manifest.copy(metrics = manifest.metrics + listOf(filtered, joined, share, difference, nested,
            inputShare, unaliasedShare, selfShare, invalidFilter, filteredConversion)),
            SqlPlanRendererRegistry.of(SqlPlanRendererRegistration(SqlEngine.BIGQUERY, BigQuerySqlPlanRenderer())))
            .explain(MetricFlowExplainRequest(names, emptyList(), filters, emptyList(), null, null, null, null,
                false, true, false, SqlEngine.BIGQUERY))
    }

    private fun assertRatioBindings(sql: String, numerator: String, denominator: String) {
        assertTrue(Regex("CAST\\(MAX\\([^)]*\\.${Regex.escape(numerator)}\\)").containsMatchIn(sql), sql)
        assertTrue(Regex("NULLIF\\(MAX\\([^)]*\\.${Regex.escape(denominator)}\\)").containsMatchIn(sql), sql)
    }

    private fun filter(sql: String) = WhereFilterIntersection(listOf(WhereFilter(sql)))
}
