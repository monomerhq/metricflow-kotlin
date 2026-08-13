package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableSpecSet
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Helper for metric query planning.
 *
 * Port of `metricflow.metric_evaluation.metric_query_helper.MetricQueryHelper`.
 *
 * The Python comment notes this is a WIP — additional consolidation /
 * restructuring is expected. The Kotlin port mirrors the public surface so the
 * downstream planners and the dataflow builder can call into it unchanged.
 */
class MetricQueryHelper(private val metricLookup: MetricLookup) {

    /**
     * Get the required group-by items for inputs to a time-offset metric.
     *
     * Port of `MetricQueryHelper.resolve_group_by_specs_for_time_offset_metric_input`.
     *
     * The required group-by items may be a *superset* of the queried items because:
     *
     * - A filter may reference group-by items that are not in the query — these
     *   must be present in the input so the filter can be applied before the
     *   re-aggregation step strips them.
     * - References to a time dimension at a custom grain require the base grain
     *   to also be selected so the engine can map between them.
     */
    fun resolveGroupBySpecsForTimeOffsetMetricInput(
        queriedGroupBySpecs: Iterable<LinkableInstanceSpec>,
        filterSpecs: Iterable<WhereFilterSpec>,
    ): OrderedSet<LinkableInstanceSpec> {
        val groupBySpecsReferencedInFilters: MutableOrderedSet<LinkableInstanceSpec> = MutableOrderedSet()
        for (filterSpec in filterSpecs) {
            groupBySpecsReferencedInFilters.addAll(filterSpec.linkableSpecs)
        }

        val required: MutableOrderedSet<LinkableInstanceSpec> = MutableOrderedSet()
        required.addAll(queriedGroupBySpecs)
        required.addAll(groupBySpecsReferencedInFilters)

        val timeDimensionSpecsWithCustomGrain = LinkableSpecSet
            .createFromSpecs(required)
            .timeDimensionSpecs
            .filter { it.hasCustomGrain }

        if (timeDimensionSpecsWithCustomGrain.isNotEmpty()) {
            for (spec in timeDimensionSpecsWithCustomGrain) {
                required.add(spec.withBaseGrain())
            }
        }

        return required
    }

    /**
     * Split filters by whether they reference aggregation time dimensions.
     *
     * Port of `MetricQueryHelper.split_filters_by_aggregation_time_dimension_references`.
     *
     * **Deferred.** The Python implementation depends on
     * `MetricLookup.get_aggregation_time_dimension_specs`, which is itself a
     * W7b/W13 deferral (it needs `GroupByItemSetResolver` machinery). The
     * dataflow builder body — the only caller — is also deferred to W14. When
     * that wire-up lands, finish this method.
     */
    fun splitFiltersByAggregationTimeDimensionReferences(
        metricReference: MetricReference,
        filterSpecs: Iterable<WhereFilterSpec>,
    ): AggregationTimeDimensionFilterSplit {
        // Silence unused warnings while the lookup wiring is pending.
        @Suppress("UNUSED_VARIABLE")
        val ml = metricLookup
        @Suppress("UNUSED_VARIABLE")
        val mr = metricReference
        @Suppress("UNUSED_VARIABLE")
        val fs = filterSpecs
        throw NotImplementedError(
            "MetricQueryHelper.splitFiltersByAggregationTimeDimensionReferences depends on " +
                "MetricLookup.getAggregationTimeDimensionSpecs (deferred to W13/W14). " +
                "Returns nothing on the current explain path.",
        )
    }
}

/**
 * Split of a list of [WhereFilterSpec]s based on how they reference aggregation
 * time dimensions.
 *
 * Port of `metricflow.metric_evaluation.metric_query_helper.AggregationTimeDimensionFilterSplit`.
 */
data class AggregationTimeDimensionFilterSplit(
    /** Filters referencing only aggregation time dimensions. */
    val filtersWithOnlyAggTimeDimensionReferences: List<WhereFilterSpec>,
    /** Filters referencing aggregation time dimensions and other linkable specs. */
    val filtersWithMixedReferences: List<WhereFilterSpec>,
    /** Filters that do not reference aggregation time dimensions (including filters with no linkable specs). */
    val filtersWithoutAggTimeDimensionReferences: List<WhereFilterSpec>,
)
