package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExpandedTimeGranularityTest {

    @Test
    fun `standard granularity round-trips`() {
        val expanded = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.MONTH)
        assertEquals("month", expanded.name)
        assertEquals(TimeGranularity.MONTH, expanded.baseGranularity)
        assertFalse(expanded.isCustomGranularity)
    }

    @Test
    fun `custom granularity is detected`() {
        val expanded = ExpandedTimeGranularity(name = "fiscal_quarter", baseGranularity = TimeGranularity.MONTH)
        assertTrue(expanded.isCustomGranularity)
    }

    @Test
    fun `standard granularity name mismatch is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ExpandedTimeGranularity(name = "month", baseGranularity = TimeGranularity.DAY)
        }
    }

    @Test
    fun `isStandardGranularityName matches enum values`() {
        assertTrue(ExpandedTimeGranularity.isStandardGranularityName("day"))
        assertTrue(ExpandedTimeGranularity.isStandardGranularityName("month"))
        assertFalse(ExpandedTimeGranularity.isStandardGranularityName("fiscal_quarter"))
    }

    @Test
    fun `comparison sorts by name first then base granularity`() {
        val a = ExpandedTimeGranularity(name = "day", baseGranularity = TimeGranularity.DAY)
        val b = ExpandedTimeGranularity(name = "month", baseGranularity = TimeGranularity.MONTH)
        val c = ExpandedTimeGranularity(name = "alpha", baseGranularity = TimeGranularity.DAY)
        val sorted = listOf(a, b, c).sorted()
        assertEquals(listOf("alpha", "day", "month"), sorted.map { it.name })
    }
}
