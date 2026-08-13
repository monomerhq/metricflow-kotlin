package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.query.parameter.DimensionOrEntityParameter
import cc.monomer.metricflow.domain.query.parameter.MetricParameter
import cc.monomer.metricflow.domain.query.parameter.OrderByParameter
import cc.monomer.metricflow.domain.query.parameter.TimeDimensionParameter
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class QueryParamImplementationsTest {

    private fun loadManifest(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot") ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/simple_manifest.json")
        require(file.isFile) { "simple_manifest.json fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `MetricParameter produces ResolverInputForMetric`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val param = MetricParameter(name = "bookings", alias = null)
        val input = param.queryResolverInput(lookup)
        assertNotNull(input.specPattern)
        assertEquals(null, input.alias)
    }

    @Test
    fun `DimensionOrEntityParameter produces ResolverInputForGroupByItem`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val param = DimensionOrEntityParameter(name = "listing__country", alias = null)
        val input = param.queryResolverInput(lookup)
        assertNotNull(input.specPattern)
    }

    @Test
    fun `TimeDimensionParameter retains grain and date_part`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val param = TimeDimensionParameter(
            name = "metric_time",
            grain = "day",
            datePart = DatePart.YEAR,
            alias = "yyyy",
        )
        val input = param.queryResolverInput(lookup)
        assertEquals("yyyy", input.alias)
    }

    @Test
    fun `OrderByParameter requires a known wrapper`() {
        assertFailsWith<IllegalArgumentException> {
            OrderByParameter(orderBy = "not_a_parameter", descending = true)
        }
    }

    @Test
    fun `OrderByParameter with metric inner roundtrips alias`() {
        val inner = MetricParameter(name = "bookings", alias = "b1")
        val outer = OrderByParameter(orderBy = inner, descending = true)
        val replaced = outer.withAlias(null)
        assertEquals(null, (replaced.orderBy as MetricParameter).alias)
    }
}
