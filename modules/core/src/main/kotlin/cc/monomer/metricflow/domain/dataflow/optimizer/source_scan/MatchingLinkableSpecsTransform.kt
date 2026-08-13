package cc.monomer.metricflow.domain.dataflow.optimizer.source_scan

import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.InstanceSpecSetTransform

/**
 * Returns `true` iff two [InstanceSpecSet]s have the same set of linkable specs. Port of
 * `metricflow.dataflow.optimizer.source_scan.matching_linkable_specs.MatchingLinkableSpecsTransform`.
 *
 * Used by [ComputeMetricsBranchCombiner] to determine whether the projections of two `SelectorNode`s
 * are compatible for combining: only matching linkable specs guarantee that the downstream
 * aggregates will produce values congruent with the original branches.
 */
class MatchingLinkableSpecsTransform(private val leftSpecSet: InstanceSpecSet) : InstanceSpecSetTransform<Boolean> {
    override fun transform(specSet: InstanceSpecSet): Boolean =
        leftSpecSet.dimensionSpecs.toSet() == specSet.dimensionSpecs.toSet() &&
            leftSpecSet.timeDimensionSpecs.toSet() == specSet.timeDimensionSpecs.toSet() &&
            leftSpecSet.entitySpecs.toSet() == specSet.entitySpecs.toSet() &&
            leftSpecSet.groupByMetricSpecs.toSet() == specSet.groupByMetricSpecs.toSet()
}
