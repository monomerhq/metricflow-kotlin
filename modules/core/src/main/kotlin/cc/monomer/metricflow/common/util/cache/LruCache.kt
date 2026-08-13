package cc.monomer.metricflow.common.util.cache

import java.util.LinkedHashMap

/**
 * An LRU cache backed by a `LinkedHashMap` in access order.
 *
 * Port of `metricflow_semantics.toolkit.cache.lru_cache.LruCache`. Python
 * implements LRU by deleting and re-inserting the touched key in an
 * insertion-ordered dict; Kotlin's `LinkedHashMap(accessOrder = true)` does
 * the same thing natively, so we delegate to it and guard everything with
 * `synchronized` (matching Python's `threading.Lock()`).
 *
 * Semantics preserved from Python:
 * - [get] re-positions the entry as "most recently used".
 * - [set] is a no-op if the key already exists (matches Python's `if key in cache: return`).
 * - When at capacity, the oldest entry is evicted on insert.
 */
class LruCache<K : Any, V : Any>(private val maxCacheItems: Int) {

    init {
        require(maxCacheItems > 0) { "maxCacheItems must be > 0 (got $maxCacheItems)" }
    }

    private val cacheDict: LinkedHashMap<K, V> = LinkedHashMap(maxCacheItems, 0.75f, true)
    private val lock = Any()

    /** Returns the cached value, marking the key as most-recently used. `null` if absent. */
    fun get(key: K): V? = synchronized(lock) { cacheDict[key] }

    /** Inserts [value]. No-op if [key] already present. Evicts oldest on overflow. */
    fun set(key: K, value: V) {
        synchronized(lock) {
            if (cacheDict.containsKey(key)) return
            while (cacheDict.size >= maxCacheItems) {
                val oldest = cacheDict.keys.iterator().next()
                cacheDict.remove(oldest)
            }
            cacheDict[key] = value
        }
    }

    /** Snapshot the current size — primarily for tests. */
    val size: Int
        get() = synchronized(lock) { cacheDict.size }

    /** Returns an independent copy. */
    fun copy(): LruCache<K, V> {
        val out = LruCache<K, V>(maxCacheItems)
        synchronized(lock) {
            for ((k, v) in cacheDict) out.cacheDict[k] = v
        }
        return out
    }
}
