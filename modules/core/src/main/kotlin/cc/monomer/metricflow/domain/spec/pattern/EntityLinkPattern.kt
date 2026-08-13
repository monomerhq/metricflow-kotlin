package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.GroupByMetricSpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * The fields of [SpecPatternParameterSet] used for matching in
 * [EntityLinkPattern].
 *
 * Port of `metricflow_semantics.specs.patterns.entity_link_pattern.ParameterSetField`.
 */
enum class ParameterSetField(val value: String) {
    ELEMENT_NAME("element_name"),
    ENTITY_LINKS("entity_links"),
    TIME_GRANULARITY("time_granularity_name"),
    DATE_PART("date_part"),
    METRIC_SUBQUERY_ENTITY_LINKS("metric_subquery_entity_links"),
}

/**
 * The parameters that an [EntityLinkPattern] compares against candidate specs.
 *
 * Port of `metricflow_semantics.specs.patterns.entity_link_pattern.SpecPatternParameterSet`.
 *
 * [fieldsToCompare] is the subset of fields the pattern enforces — fields not
 * mentioned are ignored. Sorting fieldsToCompare keeps two equivalent
 * parameter sets structurally equal (matching Python's `__post_init__`).
 */
data class SpecPatternParameterSet(
    val fieldsToCompare: List<ParameterSetField>,
    val elementName: String?,
    val entityLinks: List<EntityReference>?,
    val timeGranularityName: String?,
    val datePart: DatePart?,
    val metricSubqueryEntityLinks: List<EntityReference>?,
    val descending: Boolean?,
) {
    init {
        check(fieldsToCompare == fieldsToCompare.sortedBy { it.value }) {
            "`fieldsToCompare` must be sorted by enum value."
        }
    }

    companion object {
        /**
         * Build a parameter set, sorting `fieldsToCompare` as Python does in
         * `from_parameters`. Use this rather than the raw constructor so the
         * sort invariant is satisfied implicitly.
         */
        fun fromParameters(
            fieldsToCompare: Iterable<ParameterSetField>,
            elementName: String?,
            entityLinks: Iterable<EntityReference>?,
            timeGranularityName: String?,
            datePart: DatePart?,
            metricSubqueryEntityLinks: List<EntityReference>?,
            descending: Boolean?,
        ): SpecPatternParameterSet = SpecPatternParameterSet(
            fieldsToCompare = fieldsToCompare.sortedBy { it.value },
            elementName = elementName,
            entityLinks = entityLinks?.toList(),
            timeGranularityName = timeGranularityName,
            datePart = datePart,
            metricSubqueryEntityLinks = metricSubqueryEntityLinks,
            descending = descending,
        )
    }
}

/**
 * Match group-by-items by entity-link-path specification.
 *
 * Port of `metricflow_semantics.specs.patterns.entity_link_pattern.EntityLinkPattern`.
 *
 * The entity link path encodes how a group-by-item should be constructed via
 * a series of entity joins. The specified entity links act as a **suffix
 * match** against the candidate spec's entity links — multiple matches are
 * disambiguated by preferring the shortest entity-link path (Python's logic
 * exactly).
 */
open class EntityLinkPattern(val parameterSet: SpecPatternParameterSet) : SpecPattern {

    private fun matchEntityLinks(candidates: List<LinkableInstanceSpec>): List<LinkableInstanceSpec> {
        val target = checkNotNull(parameterSet.entityLinks)
        val n = target.size
        val matching = candidates.filter { candidate ->
            val candidateLinks = candidate.entityLinks
            target.takeLast(n) == candidateLinks.takeLast(n)
        }
        if (matching.size <= 1) return matching
        val shortest = matching.minOf { it.entityLinks.size }
        return matching.filter { it.entityLinks.size == shortest }
    }

    private fun matchTimeGranularities(
        candidates: List<LinkableInstanceSpec>,
    ): List<LinkableInstanceSpec> {
        val targetGrain = parameterSet.timeGranularityName?.lowercase()
        val asTimeDims = groupSpecsByType(candidates).timeDimensionSpecs
        return asTimeDims.filter { it.timeGranularityName == targetGrain }
    }

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        var filtered: List<LinkableInstanceSpec> = groupSpecsByType(candidateSpecs).linkableSpecs

        if (ParameterSetField.ENTITY_LINKS in parameterSet.fieldsToCompare) {
            filtered = matchEntityLinks(filtered)
        }
        if (ParameterSetField.TIME_GRANULARITY in parameterSet.fieldsToCompare) {
            filtered = matchTimeGranularities(filtered)
        }

        val otherKeys = parameterSet.fieldsToCompare.filter {
            it != ParameterSetField.ENTITY_LINKS && it != ParameterSetField.TIME_GRANULARITY
        }

        return filtered.filter { spec -> matchesAllOtherKeys(spec, otherKeys) }
    }

    private fun matchesAllOtherKeys(spec: LinkableInstanceSpec, otherKeys: List<ParameterSetField>): Boolean {
        for (key in otherKeys) {
            when (key) {
                ParameterSetField.ELEMENT_NAME -> if (spec.elementName != parameterSet.elementName) return false
                ParameterSetField.DATE_PART ->
                    if ((spec as? TimeDimensionSpec)?.datePart != parameterSet.datePart) return false
                ParameterSetField.METRIC_SUBQUERY_ENTITY_LINKS ->
                    if ((spec as? GroupByMetricSpec)?.metricSubqueryEntityLinks != parameterSet.metricSubqueryEntityLinks) {
                        return false
                    }
                else -> { /* ENTITY_LINKS, TIME_GRANULARITY handled separately */ }
            }
        }
        return true
    }

    override val elementPreFilter: GroupByItemSetFilter
        get() {
            val elementNames: Set<String>? =
                if (ParameterSetField.ELEMENT_NAME in parameterSet.fieldsToCompare && parameterSet.elementName != null) {
                    setOf(parameterSet.elementName)
                } else {
                    null
                }
            val subqueryLinks = parameterSet.metricSubqueryEntityLinks
            return if (subqueryLinks.isNullOrEmpty()) {
                GroupByItemSetFilter.create(
                    elementNameAllowlist = elementNames,
                    anyPropertiesAllowlist = null,
                    anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
                )
            } else {
                GroupByItemSetFilter.create(
                    elementNameAllowlist = elementNames,
                    anyPropertiesAllowlist = null,
                    anyPropertiesDenylist = null,
                )
            }
        }
}
