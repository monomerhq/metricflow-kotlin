package cc.monomer.metricflow.common.util.collections

/**
 * Returns the keys common to every map in [maps], preserving the iteration
 * order of the first map.
 *
 * Mirrors `metricflow_semantics.toolkit.collections.mapping_helpers.mf_common_keys`.
 */
fun <K> mfCommonKeys(maps: List<Map<K, *>>): Set<K> {
    if (maps.isEmpty()) return emptySet()
    val first = maps[0]
    val result = LinkedHashSet(first.keys)
    for (m in maps.drop(1)) result.retainAll(m.keys)
    return result
}

/**
 * Returns the entries of [mapping] sorted by key.
 *
 * Mirrors `mf_sort_by_key`. Python uses `sorted(mapping)`; Kotlin requires the
 * key type to be [Comparable] explicitly.
 */
fun <K : Comparable<K>, V> mfSortByKey(mapping: Map<K, V>): Map<K, V> {
    val sortedKeys = mapping.keys.sorted()
    val result = LinkedHashMap<K, V>(mapping.size)
    for (k in sortedKeys) result[k] = mapping.getValue(k)
    return result
}

/**
 * Returns successive `chunkSize`-sized slices of [seq].
 *
 * Mirrors `metricflow_semantics.toolkit.collections.sequence_helpers.mf_chunk`.
 * Kotlin stdlib has `List.chunked(size)` — we re-export under the
 * metricflow name to keep call sites readable.
 */
fun <T> mfChunk(seq: List<T>, chunkSize: Int): List<List<T>> = seq.chunked(chunkSize)
