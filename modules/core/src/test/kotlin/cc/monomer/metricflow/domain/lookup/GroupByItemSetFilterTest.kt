package cc.monomer.metricflow.domain.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupByItemSetFilterTest {

    @Test
    fun `EMPTY allows everything`() {
        assertTrue(GroupByItemSetFilter.EMPTY.allow("anything", listOf(GroupByItemProperty.LOCAL)))
        assertTrue(GroupByItemSetFilter.EMPTY.allow(null, null))
    }

    @Test
    fun `element name allowlist denies non-listed names`() {
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("country"),
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        assertTrue(filter.allow("country", null))
        assertFalse(filter.allow("region", null))
    }

    @Test
    fun `null elementName skips name check`() {
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("country"),
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        assertTrue(filter.allow(null, listOf(GroupByItemProperty.LOCAL)))
    }

    @Test
    fun `properties allowlist requires at least one intersection`() {
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = listOf(GroupByItemProperty.LOCAL),
            anyPropertiesDenylist = null,
        )
        assertTrue(filter.allow("x", listOf(GroupByItemProperty.LOCAL, GroupByItemProperty.JOINED)))
        assertFalse(filter.allow("x", listOf(GroupByItemProperty.JOINED)))
    }

    @Test
    fun `properties denylist trumps allowlist`() {
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = listOf(GroupByItemProperty.LOCAL),
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
        assertFalse(filter.allow("x", listOf(GroupByItemProperty.LOCAL, GroupByItemProperty.METRIC)))
    }

    @Test
    fun `merge unions allowlists and denylists`() {
        val left = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("a"),
            anyPropertiesAllowlist = listOf(GroupByItemProperty.LOCAL),
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
        val right = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("b"),
            anyPropertiesAllowlist = listOf(GroupByItemProperty.JOINED),
            anyPropertiesDenylist = listOf(GroupByItemProperty.ENTITY),
        )
        val merged = left.merge(right)
        assertEquals(setOf("a", "b"), merged.elementNameAllowlist)
        assertEquals(
            setOf(GroupByItemProperty.LOCAL, GroupByItemProperty.JOINED),
            merged.anyPropertiesAllowlist,
        )
        assertEquals(
            setOf(GroupByItemProperty.METRIC, GroupByItemProperty.ENTITY),
            merged.anyPropertiesDenylist,
        )
    }

    @Test
    fun `merge preserves null elementNameAllowlist if both sides are null`() {
        val left = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        val right = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        assertNull(left.merge(right).elementNameAllowlist)
    }

    @Test
    fun `merge replaces null elementNameAllowlist with the other side when only one is null`() {
        val left = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        val right = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("b"),
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        // Python: union(set(), set('b')) == {'b'} when either side is non-null.
        assertEquals(setOf("b"), left.merge(right).elementNameAllowlist)
    }

    @Test
    fun `withoutElementNameAllowlist clears the allowlist field`() {
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("a"),
            anyPropertiesAllowlist = listOf(GroupByItemProperty.LOCAL),
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
        val stripped = filter.withoutElementNameAllowlist()
        assertNull(stripped.elementNameAllowlist)
        assertEquals(filter.anyPropertiesAllowlist, stripped.anyPropertiesAllowlist)
        assertEquals(filter.anyPropertiesDenylist, stripped.anyPropertiesDenylist)
    }
}
