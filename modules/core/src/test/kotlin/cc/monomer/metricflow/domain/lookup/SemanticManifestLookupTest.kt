package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SemanticManifestLookupTest {

    @Test
    fun `composition root wires the sub-lookups`() {
        val lookup = SemanticManifestLookup(LookupFixtures.manifest())

        assertNotNull(lookup.semanticModelLookup.getByReference(SemanticModelReference("bookings_source")))
        assertNotNull(lookup.metricLookup.getMetric(MetricReference("bookings_count")))

        // Time spine with grain DAY is registered.
        assertTrue(TimeGranularity.DAY in lookup.timeSpineSources.keys)
    }

    @Test
    fun `custom granularities passed through to SemanticModelLookup`() {
        val lookup = SemanticManifestLookup(LookupFixtures.manifest())
        assertEquals(
            lookup.customGranularities.keys.toList(),
            lookup.semanticModelLookup.customGranularityNames,
        )
    }
}
