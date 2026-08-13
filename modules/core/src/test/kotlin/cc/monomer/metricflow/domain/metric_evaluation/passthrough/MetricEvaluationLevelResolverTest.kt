package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.domain.metric_evaluation.MetricEvaluationFixtures
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricEvaluationLevelResolverTest {

    @Test
    fun `simple metric is level zero`() {
        val lookup = ManifestObjectLookup(MetricEvaluationFixtures.manifest())
        val resolver = MetricEvaluationLevelResolver(lookup)
        assertEquals(0, resolver.resolveEvaluationLevel("bookings"))
        assertEquals(0, resolver.resolveEvaluationLevel("listings"))
    }

    @Test
    fun `derived metric is one above max input level`() {
        val lookup = ManifestObjectLookup(MetricEvaluationFixtures.manifest())
        val resolver = MetricEvaluationLevelResolver(lookup)
        assertEquals(1, resolver.resolveEvaluationLevel("bookings_per_listing"))
    }

    @Test
    fun `level is cached - repeated calls return same value`() {
        val lookup = ManifestObjectLookup(MetricEvaluationFixtures.manifest())
        val resolver = MetricEvaluationLevelResolver(lookup)
        val first = resolver.resolveEvaluationLevel("bookings_per_listing")
        val second = resolver.resolveEvaluationLevel("bookings_per_listing")
        assertEquals(first, second)
    }
}
