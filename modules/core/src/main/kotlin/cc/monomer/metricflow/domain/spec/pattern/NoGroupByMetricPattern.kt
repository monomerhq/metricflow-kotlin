package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match linkable specs, dropping any group-by metric specs.
 *
 * Port of `metricflow_semantics.specs.patterns.no_group_by_metric.NoGroupByMetricPattern`.
 *
 * Group-by metrics are valid in filter expressions but disallowed as direct
 * query-input group-by items.
 */
data object NoGroupByMetricPattern : SpecPattern {
    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        val out = mutableListOf<LinkableInstanceSpec>()
        out.addAll(specSet.timeDimensionSpecs)
        out.addAll(specSet.dimensionSpecs)
        out.addAll(specSet.entitySpecs)
        return out
    }

    override val elementPreFilter: GroupByItemSetFilter
        get() = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
}
