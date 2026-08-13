package cc.monomer.metricflow.common.util

/**
 * Objects that can be merged together into a superset of the same type.
 *
 * Port of `metricflow_semantics.toolkit.merger.Mergeable`. The Python
 * version uses an abstract `empty_instance()` classmethod and a `merge`
 * instance method; Kotlin doesn't have classmethods so callers must supply
 * the empty instance to [mergeIterable] explicitly.
 *
 * Typical usage:
 *
 * ```kotlin
 * data class IssueSet(val issues: List<String>) : Mergeable<IssueSet> {
 *   override fun merge(other: IssueSet) = IssueSet(issues + other.issues)
 * }
 *
 * val combined = Mergeable.mergeIterable(parts, empty = IssueSet(emptyList()))
 * ```
 */
interface Mergeable<T : Mergeable<T>> {
    /** Return a new object that is the result of merging `this` with [other]. */
    fun merge(other: T): T

    companion object {
        /** Merge every element of [items] into one, starting from [empty]. */
        fun <T : Mergeable<T>> mergeIterable(items: Iterable<T>, empty: T): T =
            items.fold(empty) { acc, item -> acc.merge(item) }
    }
}
