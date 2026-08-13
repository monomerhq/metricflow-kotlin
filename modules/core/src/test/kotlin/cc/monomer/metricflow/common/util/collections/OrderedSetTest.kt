package cc.monomer.metricflow.common.util.collections

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrderedSetTest {

    @Test
    fun `frozen ordered set preserves insertion order`() {
        val set = FrozenOrderedSet(listOf("c", "a", "b"))
        assertEquals(listOf("c", "a", "b"), set.toList())
    }

    @Test
    fun `duplicates are dropped`() {
        val set = FrozenOrderedSet(listOf("a", "b", "a"))
        assertEquals(listOf("a", "b"), set.toList())
    }

    @Test
    fun `equality ignores order`() {
        val a = FrozenOrderedSet(listOf("a", "b"))
        val b = FrozenOrderedSet(listOf("b", "a"))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `union concatenates preserving order`() {
        val a = FrozenOrderedSet(listOf("a", "b"))
        val u = a.union(listOf("c", "a", "d"))
        assertEquals(listOf("a", "b", "c", "d"), u.toList())
    }

    @Test
    fun `intersection preserves order of receiver`() {
        val a = FrozenOrderedSet(listOf("a", "b", "c"))
        val i = a.intersection(listOf("c", "a"))
        assertEquals(listOf("a", "c"), i.toList())
    }

    @Test
    fun `difference excludes items in others`() {
        val a = FrozenOrderedSet(listOf("a", "b", "c"))
        val d = a.difference(listOf("b"))
        assertEquals(listOf("a", "c"), d.toList())
    }

    @Test
    fun `mutable add removes returns boolean`() {
        val m = MutableOrderedSet<String>()
        assertTrue(m.add("a"))
        assertFalse(m.add("a"))
        assertTrue(m.remove("a"))
        assertFalse(m.remove("a"))
    }

    @Test
    fun `mutable pop removes first inserted`() {
        val m = MutableOrderedSet<String>().apply { addAll(listOf("a", "b", "c")) }
        assertEquals("a", m.pop())
        assertEquals(listOf("b", "c"), m.toList())
    }

    @Test
    fun `mutable pop throws when empty`() {
        val m = MutableOrderedSet<String>()
        assertFailsWith<NoSuchElementException> { m.pop() }
    }

    @Test
    fun `as frozen returns immutable snapshot`() {
        val m = MutableOrderedSet<String>().apply { addAll(listOf("a", "b")) }
        val f = m.asFrozen()
        m.add("c")
        assertEquals(listOf("a", "b"), f.toList())
    }
}

class CollectionHelpersTest {
    @Test
    fun `mfCommonKeys returns intersection of keys`() {
        val maps = listOf(mapOf("a" to 1, "b" to 2), mapOf("a" to 9, "c" to 3))
        assertEquals(setOf("a"), mfCommonKeys(maps))
    }

    @Test
    fun `mfCommonKeys empty returns empty`() {
        assertEquals(emptySet(), mfCommonKeys(emptyList<Map<String, Int>>()))
    }

    @Test
    fun `mfSortByKey sorts by key`() {
        val sorted = mfSortByKey(mapOf("b" to 1, "a" to 2, "c" to 3))
        assertEquals(listOf("a", "b", "c"), sorted.keys.toList())
    }

    @Test
    fun `mfChunk slices`() {
        assertEquals(listOf(listOf(1, 2), listOf(3, 4), listOf(5)), mfChunk(listOf(1, 2, 3, 4, 5), 2))
    }
}
