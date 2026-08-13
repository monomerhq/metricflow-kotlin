package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.spec.InstanceSpec

/**
 * A pattern that selects specs from a group of candidates based on
 * class-defined criteria.
 *
 * Port of `metricflow_semantics.specs.patterns.spec_pattern.SpecPattern`.
 *
 * Patterns drive every "find a matching dimension / entity / metric / time-
 * dimension" lookup during query resolution. The interface is intentionally
 * small: callers supply candidates, the pattern returns the subset that
 * matches.
 */
interface SpecPattern {
    /** Given candidate specs, return the ones that match this pattern. */
    fun match(candidateSpecs: Iterable<InstanceSpec>): List<InstanceSpec>

    /** `true` if this pattern matches any of the given specs. */
    fun matchesAny(candidateSpecs: Iterable<InstanceSpec>): Boolean = match(candidateSpecs).isNotEmpty()

    /**
     * Returns a filter that can produce a superset of the elements that
     * will match, allowing callers to prune the candidate set up front.
     *
     * Default: a no-op filter (allow everything).
     */
    val elementPreFilter: GroupByItemSetFilter
        get() = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
}

/** Internal helper: pre-filter denying group-by-metric items. */
internal val DENY_GROUP_BY_METRIC: GroupByItemSetFilter = GroupByItemSetFilter.create(
    elementNameAllowlist = null,
    anyPropertiesAllowlist = null,
    anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
)
