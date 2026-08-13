package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StructuredLinkableSpecNameTest {

    @Test
    fun `parses bare element name`() {
        val name = StructuredLinkableSpecName.fromName("ds", customGranularityNames = emptyList())
        assertEquals("ds", name.elementName)
        assertEquals(emptyList(), name.entityLinkNames)
        assertNull(name.timeGranularityName)
    }

    @Test
    fun `parses element_grain`() {
        val name = StructuredLinkableSpecName.fromName("ds__month", customGranularityNames = emptyList())
        assertEquals("ds", name.elementName)
        assertEquals("month", name.timeGranularityName)
    }

    @Test
    fun `parses entity__element__grain`() {
        val name = StructuredLinkableSpecName.fromName("listing__ds__week", customGranularityNames = emptyList())
        assertEquals(listOf("listing"), name.entityLinkNames)
        assertEquals("ds", name.elementName)
        assertEquals("week", name.timeGranularityName)
    }

    @Test
    fun `parses entity__element when no grain matches`() {
        val name = StructuredLinkableSpecName.fromName("listing__country", customGranularityNames = emptyList())
        assertEquals(listOf("listing"), name.entityLinkNames)
        assertEquals("country", name.elementName)
        assertNull(name.timeGranularityName)
    }

    @Test
    fun `parses custom granularity when configured`() {
        val name = StructuredLinkableSpecName.fromName(
            "ds__fiscal_q",
            customGranularityNames = listOf("fiscal_q"),
        )
        assertEquals("ds", name.elementName)
        assertEquals("fiscal_q", name.timeGranularityName)
    }

    @Test
    fun `lowercases inputs`() {
        val name = StructuredLinkableSpecName(
            entityLinkNames = listOf("LISTING"),
            elementName = "DS",
            timeGranularityName = "MONTH",
            datePart = null,
            metricSubqueryEntityLinkNames = null,
        )
        assertEquals(listOf("listing"), name.entityLinkNames)
        assertEquals("ds", name.elementName)
        assertEquals("month", name.timeGranularityName)
    }

    @Test
    fun `dunderName uses date_part suffix when present`() {
        val name = StructuredLinkableSpecName(
            entityLinkNames = listOf("listing"),
            elementName = "ds",
            timeGranularityName = "day",
            datePart = DatePart.YEAR,
            metricSubqueryEntityLinkNames = null,
        )
        // date_part wins → granularity dropped.
        assertEquals("listing__ds__extract_year", name.dunderName)
    }

    @Test
    fun `metric subquery links append when distinct`() {
        val name = StructuredLinkableSpecName(
            entityLinkNames = listOf("listing"),
            elementName = "bookings",
            timeGranularityName = null,
            datePart = null,
            metricSubqueryEntityLinkNames = listOf("user"),
        )
        assertEquals("listing__user__bookings", name.dunderName)
    }

    @Test
    fun `metric subquery links collapse when equal`() {
        val name = StructuredLinkableSpecName(
            entityLinkNames = listOf("listing"),
            elementName = "bookings",
            timeGranularityName = null,
            datePart = null,
            metricSubqueryEntityLinkNames = listOf("listing"),
        )
        assertEquals("listing__bookings", name.dunderName)
    }

    @Test
    fun `rejects extract_-suffixed dunder syntax`() {
        assertFailsWith<IllegalArgumentException> {
            StructuredLinkableSpecName.fromName("ds__extract_year", customGranularityNames = emptyList())
        }
    }
}
