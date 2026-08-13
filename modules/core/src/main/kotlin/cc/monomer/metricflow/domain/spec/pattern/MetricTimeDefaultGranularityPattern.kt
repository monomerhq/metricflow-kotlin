package cc.monomer.metricflow.domain.spec.pattern

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.DEFAULT_TIME_GRANULARITY
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpecComparisonKey
import cc.monomer.metricflow.domain.spec.TimeDimensionSpecField
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Match `metric_time` specs to the default granularity for the requested
 * metrics, leaving every other spec untouched.
 *
 * Port of
 * `metricflow_semantics.specs.patterns.metric_time_default_granularity.MetricTimeDefaultGranularityPattern`.
 *
 * The default granularity is the max of the requested metrics' default
 * granularities, falling back to [DEFAULT_TIME_GRANULARITY]. Specs without a
 * grain pass through unchanged.
 */
data class MetricTimeDefaultGranularityPattern(
    val maxMetricDefaultTimeGranularity: TimeGranularity?,
) : SpecPattern {

    override fun match(candidateSpecs: Iterable<InstanceSpec>): List<InstanceSpec> {
        val candidates: List<InstanceSpec> = candidateSpecs.toList()
        val specSet = groupSpecsByType(candidates)

        // If there are no metric_time specs in the query, skip this filter.
        if (specSet.metricTimeSpecs.isEmpty()) return candidates

        val defaultGranularity = ExpandedTimeGranularity.fromTimeGranularity(
            maxMetricDefaultTimeGranularity ?: DEFAULT_TIME_GRANULARITY,
        )

        val noGrain = mutableListOf<TimeDimensionSpec>()
        val keyToGrains = LinkedHashMap<TimeDimensionSpecComparisonKey, MutableSet<ExpandedTimeGranularity>>()
        val keyToSpecs = LinkedHashMap<TimeDimensionSpecComparisonKey, MutableList<TimeDimensionSpec>>()
        for (spec in specSet.metricTimeSpecs) {
            if (spec.timeGranularity == null) {
                noGrain.add(spec)
                continue
            }
            val key = spec.comparisonKey(setOf(TimeDimensionSpecField.TIME_GRANULARITY))
            keyToGrains.getOrPut(key) { LinkedHashSet() }.add(spec.timeGranularity)
            keyToSpecs.getOrPut(key) { mutableListOf() }.add(spec)
        }

        val matchedMetricTime = mutableListOf<TimeDimensionSpec>()
        for ((key, grains) in keyToGrains) {
            if (defaultGranularity in grains) {
                matchedMetricTime.add(keyToSpecs.getValue(key).first().withGrain(defaultGranularity))
            } else {
                // No default available — pass through every option for this key.
                matchedMetricTime.addAll(keyToSpecs.getValue(key))
            }
        }

        val out = mutableListOf<LinkableInstanceSpec>()
        out.addAll(specSet.dimensionSpecs)
        out.addAll(matchedMetricTime)
        out.addAll(specSet.timeDimensionSpecs.filter { !it.isMetricTime })
        out.addAll(noGrain)
        out.addAll(specSet.entitySpecs)
        out.addAll(specSet.groupByMetricSpecs)
        return out
    }
}
