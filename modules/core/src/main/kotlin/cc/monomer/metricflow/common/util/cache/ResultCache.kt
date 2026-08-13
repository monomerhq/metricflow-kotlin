package cc.monomer.metricflow.common.util.cache

/**
 * Wrapper that allows distinguishing a cached `null` value from "absent".
 *
 * Port of `metricflow_semantics.toolkit.cache.result_cache.ResultCacheEntry`.
 */
data class ResultCacheEntry<V>(val value: V)

/**
 * Cache class to simplify checking / getting / setting the cache for a result.
 *
 * Port of `metricflow_semantics.toolkit.cache.result_cache.ResultCache`. The
 * Python implementation is intentionally lock-free — used in `@cached_property`-like
 * cases where double-compute is acceptable. We preserve that semantics in
 * Kotlin.
 */
class ResultCache<K : Any, V> {

    private val cacheDict: MutableMap<K, ResultCacheEntry<V>> = HashMap()

    /** Returns the cache entry for [key], or `null` if not present. */
    fun get(key: K): ResultCacheEntry<V>? = cacheDict[key]

    /** Caches [value] under [key] and returns it. */
    fun setAndGet(key: K, value: V): V {
        cacheDict[key] = ResultCacheEntry(value)
        return value
    }

    val size: Int get() = cacheDict.size
}
