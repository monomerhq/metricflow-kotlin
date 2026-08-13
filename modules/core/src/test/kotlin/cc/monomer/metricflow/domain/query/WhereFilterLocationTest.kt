package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocation
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocationType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WhereFilterLocationTest {

    @Test
    fun `forQuery sorts metric references`() {
        val location = WhereFilterLocation.forQuery(
            listOf(MetricReference("zeta"), MetricReference("alpha")),
        )
        assertEquals(
            listOf(MetricReference("alpha"), MetricReference("zeta")),
            location.metricReferences,
        )
        assertEquals(WhereFilterLocationType.QUERY, location.locationType)
    }

    @Test
    fun `forMetric carries one reference`() {
        val location = WhereFilterLocation.forMetric(MetricReference("bookings"))
        assertEquals(WhereFilterLocationType.METRIC, location.locationType)
        assertEquals(listOf(MetricReference("bookings")), location.metricReferences)
    }

    @Test
    fun `forInputMetric uses INPUT_METRIC location type`() {
        val location = WhereFilterLocation.forInputMetric(MetricReference("ratio_metric"))
        assertEquals(WhereFilterLocationType.INPUT_METRIC, location.locationType)
    }
}
