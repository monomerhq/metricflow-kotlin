package cc.monomer.metricflow.common.util.cache

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruCacheTest {

    @Test
    fun `get returns null for missing keys`() {
        val cache = LruCache<String, Int>(3)
        assertNull(cache.get("missing"))
    }

    @Test
    fun `set then get round-trips`() {
        val cache = LruCache<String, Int>(3)
        cache.set("a", 1)
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun `set is no-op when key already present`() {
        val cache = LruCache<String, Int>(3)
        cache.set("a", 1)
        cache.set("a", 99) // Python: no-op.
        assertEquals(1, cache.get("a"))
    }

    @Test
    fun `eldest entry is evicted at capacity`() {
        val cache = LruCache<String, Int>(2)
        cache.set("a", 1)
        cache.set("b", 2)
        cache.set("c", 3)
        assertNull(cache.get("a"))
        assertEquals(2, cache.get("b"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `recent access prevents eviction`() {
        val cache = LruCache<String, Int>(2)
        cache.set("a", 1)
        cache.set("b", 2)
        cache.get("a") // promotes "a"
        cache.set("c", 3) // should evict "b" not "a"
        assertEquals(1, cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `copy is independent`() {
        val cache = LruCache<String, Int>(3)
        cache.set("a", 1)
        val clone = cache.copy()
        cache.set("b", 2)
        assertEquals(1, clone.size)
        assertEquals(2, cache.size)
    }
}

class ResultCacheTest {
    @Test
    fun `setAndGet records value and returns it`() {
        val cache = ResultCache<String, Int?>()
        val v = cache.setAndGet("k", 42)
        assertEquals(42, v)
        assertEquals(42, cache.get("k")?.value)
    }

    @Test
    fun `entry preserves a null value`() {
        val cache = ResultCache<String, String?>()
        cache.setAndGet("k", null)
        val entry = cache.get("k")
        assertNull(entry?.value)
        // Distinguishable from absent because entry itself is non-null.
        assertEquals(true, entry != null)
    }
}
