package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AggregationHelperTest {

    @Test
    fun `InstanceAliasMapping merges non-conflicting items`() {
        val a = InstanceAliasMapping.create(mapOf("bookings" to "x"))
        val b = InstanceAliasMapping.create(mapOf("booking_value" to "y"))
        val merged = a.merge(b)
        assertEquals("x", merged.elementNameToAlias["bookings"])
        assertEquals("y", merged.elementNameToAlias["booking_value"])
    }

    @Test
    fun `InstanceAliasMapping detects conflicts on same key`() {
        val a = InstanceAliasMapping.create(mapOf("bookings" to "x"))
        val b = InstanceAliasMapping.create(mapOf("bookings" to "y"))
        assertTrue(a.hasConflict(b))
        assertFailsWith<IllegalStateException> { a.merge(b) }
    }

    @Test
    fun `InstanceAliasMapping detects alias collisions on different keys`() {
        val a = InstanceAliasMapping.create(mapOf("bookings" to "x"))
        val b = InstanceAliasMapping.create(mapOf("booking_value" to "x"))
        // 'x' assigned to two different sources -> conflict
        assertTrue(a.hasConflict(b))
    }

    @Test
    fun `aliasedSpec returns a renamed spec, or null when no alias`() {
        val mapping = InstanceAliasMapping.create(mapOf("bookings" to "x"))
        val spec = SimpleMetricInputSpec(elementName = "bookings", fillNullsWith = null)
        val aliased = mapping.aliasedSpec(spec)!!
        assertEquals("x", aliased.elementName)
        // Spec without alias entry yields null
        assertNull(
            mapping.aliasedSpec(SimpleMetricInputSpec(elementName = "missing", fillNullsWith = null)),
        )
    }

    @Test
    fun `NullFillValueMapping is unchanged after no-op merge`() {
        val empty = NullFillValueMapping.EMPTY
        val merged = empty.merge(empty)
        assertEquals(emptyList(), merged.elementNameAndNullFillValueItems)
        assertFalse(empty.hasConflict(empty))
    }
}
