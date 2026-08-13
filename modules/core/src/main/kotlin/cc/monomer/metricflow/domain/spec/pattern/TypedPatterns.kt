package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match dimensions (and optionally time dimensions) by entity-link path.
 *
 * Port of `metricflow_semantics.specs.patterns.typed_patterns.DimensionPattern`.
 *
 * The Python defaults `include_time_dimensions=True`; we require callers to
 * be explicit, matching CLAUDE.md's "no default parameters" rule. A factory
 * that mirrors the Python default lives at the bottom of this file.
 */
class DimensionPattern(
    parameterSet: SpecPatternParameterSet,
    val includeTimeDimensions: Boolean,
) : EntityLinkPattern(parameterSet) {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        val candidates: List<LinkableInstanceSpec> = if (includeTimeDimensions) {
            specSet.dimensionSpecs + specSet.timeDimensionSpecs
        } else {
            specSet.dimensionSpecs
        }
        return super.match(candidates)
    }

    override val elementPreFilter: GroupByItemSetFilter
        get() = super.elementPreFilter.merge(
            GroupByItemSetFilter.create(
                elementNameAllowlist = null,
                anyPropertiesAllowlist = null,
                anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
            ),
        )
}

/**
 * Match exactly the time-dimension specs by entity-link path.
 *
 * Port of `metricflow_semantics.specs.patterns.typed_patterns.TimeDimensionPattern`.
 */
class TimeDimensionPattern(parameterSet: SpecPatternParameterSet) : EntityLinkPattern(parameterSet) {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        return super.match(specSet.timeDimensionSpecs)
    }

    override val elementPreFilter: GroupByItemSetFilter
        get() = super.elementPreFilter.merge(
            GroupByItemSetFilter.create(
                elementNameAllowlist = null,
                anyPropertiesAllowlist = null,
                anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
            ),
        )

    companion object {
        /**
         * Pick the fields to compare given a (grain, datePart) input.
         *
         * Mirrors Python's `TimeDimensionPattern.get_fields_to_compare`. If
         * date_part is requested, time granularity is ignored.
         */
        fun getFieldsToCompare(
            timeGranularityName: String?,
            datePart: DatePart?,
        ): List<ParameterSetField> {
            val fields = mutableListOf(
                ParameterSetField.ELEMENT_NAME,
                ParameterSetField.ENTITY_LINKS,
                ParameterSetField.DATE_PART,
            )
            if (datePart == null && timeGranularityName != null) {
                fields.add(ParameterSetField.TIME_GRANULARITY)
            }
            return fields
        }
    }
}

/**
 * Match exactly the entity specs by entity-link path.
 *
 * Port of `metricflow_semantics.specs.patterns.typed_patterns.EntityPattern`.
 */
class EntityPattern(parameterSet: SpecPatternParameterSet) : EntityLinkPattern(parameterSet) {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        return super.match(specSet.entitySpecs)
    }

    override val elementPreFilter: GroupByItemSetFilter
        get() = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
}

/**
 * Match group-by-metric specs by entity-link path + subquery entity links.
 *
 * Port of `metricflow_semantics.specs.patterns.typed_patterns.GroupByMetricPattern`.
 */
class GroupByMetricPattern(parameterSet: SpecPatternParameterSet) : EntityLinkPattern(parameterSet) {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        return super.match(specSet.groupByMetricSpecs)
    }
}
