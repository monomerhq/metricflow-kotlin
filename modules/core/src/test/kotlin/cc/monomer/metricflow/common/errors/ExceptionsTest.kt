package cc.monomer.metricflow.common.errors

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExceptionsTest {

    @Test
    fun `hierarchy uses MetricFlowException as root`() {
        val e: MetricFlowException = DuplicateMetricError("dup")
        assertTrue(e is SemanticException)
    }

    @Test
    fun `UnableToSatisfyQueryError formats context`() {
        val err = UnableToSatisfyQueryError(
            errorName = "bad time grain",
            context = mapOf("metric" to "x", "grain" to "month"),
        )
        val s = err.message ?: ""
        assertTrue(s.startsWith("Unable To Satisfy Query Error: bad time grain"))
        assertTrue(s.contains("metric:"))
        assertTrue(s.contains("    x"))
    }

    @Test
    fun `UnknownMetricError formats one and many names`() {
        val one = UnknownMetricError(listOf("bookings"))
        assertEquals("Unknown metric: 'bookings'", one.message)

        val many = UnknownMetricError(listOf("bookings", "revenue"))
        assertEquals("Unknown metrics: [bookings, revenue]", many.message)
    }

    @Test
    fun `UnknownMetricError zero rejects construction`() {
        assertFailsWith<RuntimeException> { UnknownMetricError(emptyList()) }
    }

    @Test
    fun `errorIfNotStandardGrain returns enum for known grain`() {
        assertEquals(TimeGranularity.MONTH, errorIfNotStandardGrain("month", context = null))
    }

    @Test
    fun `errorIfNotStandardGrain throws FeatureNotSupportedError for custom`() {
        val e = assertFailsWith<FeatureNotSupportedError> {
            errorIfNotStandardGrain("fortnight", context = "for cumulative metric")
        }
        assertTrue(e.message!!.contains("fortnight"))
        assertTrue(e.message!!.contains("Context: for cumulative metric"))
    }
}
