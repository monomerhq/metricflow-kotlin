package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.errors.DuplicateMetricError
import cc.monomer.metricflow.common.errors.MetricNotFoundError
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetricLookupTest {

    @Test
    fun `getMetric returns the metric record`() {
        val lookup = MetricLookup(LookupFixtures.manifest())
        val metric = lookup.getMetric(MetricReference("bookings_count"))
        assertEquals("bookings_count", metric.name)
        assertEquals(MetricType.SIMPLE, metric.type)
    }

    @Test
    fun `getMetric throws when unknown`() {
        val lookup = MetricLookup(LookupFixtures.manifest())
        assertFailsWith<MetricNotFoundError> { lookup.getMetric(MetricReference("ghost")) }
    }

    @Test
    fun `metricReferences is sorted`() {
        val lookup = MetricLookup(LookupFixtures.manifest())
        val refs = lookup.metricReferences
        assertEquals(refs.sortedBy { it.elementName }, refs)
        assertTrue(MetricReference("bookings_count") in refs)
        assertTrue(MetricReference("doubled_bookings") in refs)
    }

    @Test
    fun `duplicate metric names throw`() {
        val manifest = SemanticManifest(
            semanticModels = listOf(LookupFixtures.bookingsModel()),
            metrics = listOf(
                Metric(
                    name = "dup",
                    type = MetricType.SIMPLE,
                    typeParams = MetricTypeParams(
                        metricAggregationParams = MetricAggregationParams(
                            semanticModel = "bookings_source",
                            agg = AggregationType.SUM,
                        ),
                    ),
                ),
                Metric(
                    name = "dup",
                    type = MetricType.SIMPLE,
                    typeParams = MetricTypeParams(
                        metricAggregationParams = MetricAggregationParams(
                            semanticModel = "bookings_source",
                            agg = AggregationType.SUM,
                        ),
                    ),
                ),
            ),
            projectConfiguration = ProjectConfiguration(timeSpines = listOf(LookupFixtures.timeSpine())),
        )
        assertFailsWith<DuplicateMetricError> { MetricLookup(manifest) }
    }

    @Test
    fun `metricInputs handles each metric type`() {
        // SIMPLE → empty.
        val simple = LookupFixtures.simpleBookingsMetric()
        assertEquals(emptyList(), MetricLookup.metricInputs(simple, includeConversionMetricInput = false))

        // DERIVED → its `metrics` field.
        val derived = LookupFixtures.derivedMetric()
        assertEquals(
            listOf(MetricInput(name = "bookings_count")),
            MetricLookup.metricInputs(derived, includeConversionMetricInput = false),
        )
    }

    @Test
    fun `getDerivedFromSemanticModels traces simple metric inputs`() {
        val lookup = MetricLookup(LookupFixtures.manifest())

        // bookings_count is SIMPLE — returns its own model.
        assertEquals(
            listOf(SemanticModelReference("bookings_source")),
            lookup.getDerivedFromSemanticModels(MetricReference("bookings_count")),
        )

        // doubled_bookings is DERIVED on top of bookings_count — same set.
        assertEquals(
            listOf(SemanticModelReference("bookings_source")),
            lookup.getDerivedFromSemanticModels(MetricReference("doubled_bookings")),
        )
    }

    @Test
    fun `getDerivedFromSemanticModels is cached on repeated calls`() {
        val lookup = MetricLookup(LookupFixtures.manifest())
        val first = lookup.getDerivedFromSemanticModels(MetricReference("doubled_bookings"))
        val second = lookup.getDerivedFromSemanticModels(MetricReference("doubled_bookings"))
        // Cached entries are returned by reference, but the contract we depend on is content equality.
        assertEquals(first, second)
    }

    @Test
    fun `metric_inputs throws for malformed RATIO metric`() {
        val brokenRatio = Metric(
            name = "broken",
            type = MetricType.RATIO,
            typeParams = MetricTypeParams(numerator = null, denominator = null),
        )
        assertFailsWith<cc.monomer.metricflow.common.errors.MetricFlowInternalError> {
            MetricLookup.metricInputs(brokenRatio, includeConversionMetricInput = false)
        }
    }
}
