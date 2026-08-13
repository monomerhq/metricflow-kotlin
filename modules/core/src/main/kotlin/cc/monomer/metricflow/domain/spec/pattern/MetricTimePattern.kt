package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match exactly the time-dimension specs whose [TimeDimensionSpec.elementName]
 * equals `metric_time`.
 *
 * Port of `metricflow_semantics.specs.patterns.metric_time_pattern.MetricTimePattern`.
 *
 * Used to determine whether `metric_time` has been specified in a query, or
 * for checks that apply only to `metric_time` specs.
 */
data object MetricTimePattern : SpecPattern {
    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<TimeDimensionSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        return specSet.timeDimensionSpecs.filter { it.elementName == METRIC_TIME_ELEMENT_NAME }
    }
}
