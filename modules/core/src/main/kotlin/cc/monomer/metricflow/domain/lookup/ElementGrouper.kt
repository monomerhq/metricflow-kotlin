package cc.monomer.metricflow.domain.lookup

/**
 * Groups an element pair such that we have `(X, List<Y>)`.
 *
 * Port of `metricflow_semantics/model/semantics/element_group.py::ElementGrouper`.
 *
 * The Python implementation uses `collections.defaultdict(list)`. Kotlin's
 * [MutableMap.getOrPut] supplies the same semantics.
 */
class ElementGrouper<X, Y> {

    private val groups: MutableMap<X, MutableList<Y>> = LinkedHashMap()

    /** Append [value] under [key], creating an entry if none exists yet. */
    fun addValue(key: X, value: Y) {
        groups.getOrPut(key) { mutableListOf() }.add(value)
    }

    /**
     * Returns the values stored under [key].
     *
     * Throws [NoSuchElementException] if [key] is unknown — Python raises `KeyError`.
     */
    fun getValues(key: X): List<Y> =
        groups[key] ?: throw NoSuchElementException("Unable to find `$key` in ElementGrouper")

    /** Insertion-ordered view of the keys currently stored in this grouper. */
    val keys: List<X>
        get() = groups.keys.toList()
}
