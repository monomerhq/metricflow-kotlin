package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match [MetricSpec]s whose reference equals [metricReference].
 *
 * Port of `metricflow_semantics.specs.patterns.metric_pattern.MetricSpecPattern`.
 */
data class MetricSpecPattern(
    val metricReference: MetricReference,
    val descending: Boolean?,
) : SpecPattern {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<MetricSpec> {
        val specSet = groupSpecsByType(candidateSpecs)
        return specSet.metricSpecs.filter { it.reference == metricReference }
    }
}
