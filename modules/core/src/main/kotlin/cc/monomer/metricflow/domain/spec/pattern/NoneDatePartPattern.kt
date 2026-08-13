package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match linkable specs, restricting time-dimension specs to those without a
 * [cc.monomer.metricflow.domain.manifest.model.enums.DatePart].
 *
 * Port of `metricflow_semantics.specs.patterns.none_date_part.NoneDatePartPattern`.
 *
 * Used by cumulative-metric restrictions where date_part group-bys are
 * disallowed.
 */
data object NoneDatePartPattern : SpecPattern {
    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        val out = mutableListOf<LinkableInstanceSpec>()
        out.addAll(specSet.timeDimensionSpecs.filter { it.datePart == null })
        out.addAll(specSet.dimensionSpecs)
        out.addAll(specSet.entitySpecs)
        out.addAll(specSet.groupByMetricSpecs)
        return out
    }
}
