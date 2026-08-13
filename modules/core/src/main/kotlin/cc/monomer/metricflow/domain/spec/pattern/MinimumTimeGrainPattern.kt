package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpecComparisonKey
import cc.monomer.metricflow.domain.spec.TimeDimensionSpecField
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match linkable specs, but for time-dimension specs only the one with the
 * finest base grain.
 *
 * Port of `metricflow_semantics.specs.patterns.minimum_time_grain.MinimumTimeGrainPattern`.
 *
 * The finest grain represents the source-defined grain. For custom
 * granularities the comparison uses the base grain. Used to implement
 * matching of group-by items inside where filters where an ambiguously
 * specified group-by item can only match the base-grain spec.
 */
data object MinimumTimeGrainPattern : SpecPattern {
    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<LinkableInstanceSpec> {
        val specSet = groupSpecsByType(candidateSpecs)

        val noGrain = mutableListOf<TimeDimensionSpec>()
        val keyToGrains = LinkedHashMap<TimeDimensionSpecComparisonKey, MutableSet<ExpandedTimeGranularity>>()
        val keyToSpecs = LinkedHashMap<TimeDimensionSpecComparisonKey, MutableList<TimeDimensionSpec>>()

        for (spec in specSet.timeDimensionSpecs) {
            if (spec.timeGranularity == null) {
                noGrain.add(spec)
                continue
            }
            val key = spec.comparisonKey(setOf(TimeDimensionSpecField.TIME_GRANULARITY))
            keyToGrains.getOrPut(key) { LinkedHashSet() }.add(spec.timeGranularity)
            keyToSpecs.getOrPut(key) { mutableListOf() }.add(spec)
        }

        val matched = mutableListOf<TimeDimensionSpec>()
        for ((key, grains) in keyToGrains) {
            // Sort by smallest to largest standard granularity, with custom grains last
            // (sorted by their base granularity since we don't know how large they are).
            val sorted = grains.sortedWith(compareBy({ it.isCustomGranularity }, { it.baseGranularity.toInt() }))
            check(sorted.isNotEmpty()) {
                "Each time dimension spec should have at least one grain, but none was found for $key."
            }
            val templateSpec = keyToSpecs.getValue(key).first()
            matched.add(templateSpec.withGrain(sorted.first()))
        }

        val out = mutableListOf<LinkableInstanceSpec>()
        out.addAll(specSet.dimensionSpecs)
        out.addAll(matched)
        out.addAll(noGrain)
        out.addAll(specSet.entitySpecs)
        out.addAll(specSet.groupByMetricSpecs)
        return out
    }
}
