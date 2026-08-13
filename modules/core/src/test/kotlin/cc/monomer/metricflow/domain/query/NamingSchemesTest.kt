package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.query.naming.DunderNamingScheme
import cc.monomer.metricflow.domain.query.naming.MetricNamingScheme
import cc.monomer.metricflow.domain.query.naming.ObjectBuilderNamingScheme
import cc.monomer.metricflow.domain.query.naming.QueryItemLocation
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamingSchemesTest {

    private fun loadManifest(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot") ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/simple_manifest.json")
        require(file.isFile) { "simple_manifest.json fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `MetricNamingScheme accepts bare names`() {
        val scheme = MetricNamingScheme()
        val lookup = SemanticManifestLookup(loadManifest())
        assertTrue(scheme.inputStrFollowsScheme("bookings", lookup, QueryItemLocation.NON_ORDER_BY))
        assertFalse(scheme.inputStrFollowsScheme("Metric('bookings')", lookup, QueryItemLocation.NON_ORDER_BY))
    }

    @Test
    fun `MetricNamingScheme builds a spec pattern`() {
        val scheme = MetricNamingScheme()
        val lookup = SemanticManifestLookup(loadManifest())
        val pattern = scheme.specPattern("bookings", lookup, QueryItemLocation.NON_ORDER_BY)
        assertEquals("bookings", pattern.metricReference.elementName)
    }

    @Test
    fun `DunderNamingScheme accepts well-formed names`() {
        val scheme = DunderNamingScheme()
        val lookup = SemanticManifestLookup(loadManifest())
        assertTrue(scheme.inputStrFollowsScheme("listing__country", lookup, QueryItemLocation.NON_ORDER_BY))
        assertTrue(scheme.inputStrFollowsScheme("metric_time__day", lookup, QueryItemLocation.NON_ORDER_BY))
        // Date_part suffix is reserved for object-builder syntax — dunder rejects.
        assertFalse(scheme.inputStrFollowsScheme("ds__extract_year", lookup, QueryItemLocation.NON_ORDER_BY))
    }

    @Test
    fun `DunderNamingScheme is case-insensitive on input`() {
        val scheme = DunderNamingScheme()
        val lookup = SemanticManifestLookup(loadManifest())
        assertTrue(scheme.inputStrFollowsScheme("Listing__Country", lookup, QueryItemLocation.NON_ORDER_BY))
    }

    @Test
    fun `ObjectBuilderNamingScheme is deferred today`() {
        val scheme = ObjectBuilderNamingScheme()
        val lookup = SemanticManifestLookup(loadManifest())
        // Until the JinjaObjectParser ports, every input is rejected.
        assertFalse(scheme.inputStrFollowsScheme("Dimension('listing__country')", lookup, QueryItemLocation.NON_ORDER_BY))
    }
}
