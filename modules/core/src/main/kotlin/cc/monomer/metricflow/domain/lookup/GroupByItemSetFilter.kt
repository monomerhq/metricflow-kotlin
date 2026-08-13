package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.util.Mergeable

/**
 * Describes a way to filter the items in a `BaseGroupByItemSet`.
 *
 * Port of `metricflow_semantics/model/semantics/element_filter.py::GroupByItemSetFilter`.
 *
 * Semantics:
 * - A `null` value for [elementNameAllowlist] means no filtering on element names.
 * - Only elements whose property set intersects [anyPropertiesAllowlist] are retained.
 * - Elements whose property set intersects [anyPropertiesDenylist] are then removed.
 *
 * The class participates in [Mergeable]: merging two filters takes the **union** of allowlists
 * (so the merged filter accepts strictly more) and the **union** of denylists (so it rejects
 * strictly more). The element-name allowlist propagates `null`-as-no-filter semantics: if either
 * side has `null`, the merged side keeps `null`.
 */
data class GroupByItemSetFilter(
    val elementNameAllowlist: Set<String>?,
    val anyPropertiesAllowlist: Set<GroupByItemProperty>,
    val anyPropertiesDenylist: Set<GroupByItemProperty>,
) : Mergeable<GroupByItemSetFilter> {

    override fun merge(other: GroupByItemSetFilter): GroupByItemSetFilter {
        val mergedElementNames: Set<String>? =
            if (elementNameAllowlist == null && other.elementNameAllowlist == null) {
                null
            } else {
                (elementNameAllowlist ?: emptySet()) + (other.elementNameAllowlist ?: emptySet())
            }
        return GroupByItemSetFilter(
            elementNameAllowlist = mergedElementNames,
            anyPropertiesAllowlist = anyPropertiesAllowlist + other.anyPropertiesAllowlist,
            anyPropertiesDenylist = anyPropertiesDenylist + other.anyPropertiesDenylist,
        )
    }

    /** Return this filter without the [elementNameAllowlist] constraint. */
    fun withoutElementNameAllowlist(): GroupByItemSetFilter =
        copy(elementNameAllowlist = null)

    /**
     * Return `true` if this filter allows an item with the given [elementName] and
     * [elementProperties]. A `null` value for either parameter means "context unavailable" and
     * the corresponding check is skipped.
     */
    fun allow(elementName: String?, elementProperties: Iterable<GroupByItemProperty>?): Boolean {
        if (elementName != null) {
            val allowed = elementNameAllowlist
            if (allowed != null && elementName !in allowed) return false
        }
        if (elementProperties != null) {
            val propertySet = elementProperties.toSet()
            if (anyPropertiesAllowlist.intersect(propertySet).isEmpty()) return false
            if (anyPropertiesDenylist.isNotEmpty() && anyPropertiesDenylist.intersect(propertySet).isNotEmpty()) {
                return false
            }
        }
        return true
    }

    companion object {
        /** A no-op filter — allows every element, regardless of name or properties. */
        val EMPTY: GroupByItemSetFilter = GroupByItemSetFilter(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = GroupByItemProperty.allProperties(),
            anyPropertiesDenylist = emptySet(),
        )

        /**
         * Factory mirroring Python's `GroupByItemSetFilter.create` keyword defaults.
         *
         * Python supplied `GroupByItemProperty.all_properties()` as the default for
         * [anyPropertiesAllowlist] and `frozenset()` as the default for [anyPropertiesDenylist].
         * Kotlin's "no default values" policy forbids us replicating those defaults verbatim, so
         * callers can either invoke this factory with `null` to mean "use the defaults" or pass
         * explicit sets.
         */
        fun create(
            elementNameAllowlist: Iterable<String>?,
            anyPropertiesAllowlist: Iterable<GroupByItemProperty>?,
            anyPropertiesDenylist: Iterable<GroupByItemProperty>?,
        ): GroupByItemSetFilter = GroupByItemSetFilter(
            elementNameAllowlist = elementNameAllowlist?.toSet(),
            anyPropertiesAllowlist = anyPropertiesAllowlist?.toSet() ?: GroupByItemProperty.allProperties(),
            anyPropertiesDenylist = anyPropertiesDenylist?.toSet() ?: emptySet(),
        )
    }
}
