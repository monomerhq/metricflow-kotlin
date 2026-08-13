package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection

/**
 * Specs needed for running a query.
 *
 * Port of `metricflow_semantics.specs.query_spec.MetricFlowQuerySpec`.
 *
 * This is the input record handed to the dataflow planner /
 * `MetricFlowEngine.explain()`. It packs every group-by item, ordering, time
 * constraint, and filter intersection a single query needs.
 *
 * **W7b/W8 note**: Python's `filter_spec_resolution_lookup` field
 * (`FilterSpecResolutionLookUp`) lives in `:domain:query`. W7b introduced
 * [FilterSpecResolutionLookupPlaceholder] as an empty marker interface so
 * `:domain:spec` could ship without pulling in the W8 dependency. W8 lands
 * the concrete type (`FilterSpecResolutionLookUp` in `:domain:query`) and
 * it implements this marker — callers see a nullable [FilterSpecResolutionLookupPlaceholder]
 * here and downcast at the `:domain:query` boundary.
 */
data class MetricFlowQuerySpec(
    val metricSpecs: List<MetricSpec>,
    val dimensionSpecs: List<DimensionSpec>,
    val entitySpecs: List<EntitySpec>,
    val timeDimensionSpecs: List<TimeDimensionSpec>,
    val groupByMetricSpecs: List<GroupByMetricSpec>,
    val orderBySpecs: List<OrderBySpec>,
    val timeRangeConstraint: TimeRangeConstraint?,
    val limit: Int?,
    val filterIntersection: WhereFilterIntersection,
    val filterSpecResolutionLookup: FilterSpecResolutionLookupPlaceholder?,
    val minMaxOnly: Boolean,
    val applyGroupBy: Boolean,
    val inputSpecOrder: InputSpecOrder,
) {

    /** View the linkable buckets as a single [LinkableSpecSet]. */
    val linkableSpecs: LinkableSpecSet
        get() = LinkableSpecSet(
            dimensionSpecs = dimensionSpecs,
            timeDimensionSpecs = timeDimensionSpecs,
            entitySpecs = entitySpecs,
            groupByMetricSpecs = groupByMetricSpecs,
        )

    /** Return a copy that's the same as `this` but with a different [timeRangeConstraint]. */
    fun withTimeRangeConstraint(timeRangeConstraint: TimeRangeConstraint?): MetricFlowQuerySpec =
        copy(timeRangeConstraint = timeRangeConstraint)
}

/**
 * Placeholder for `FilterSpecResolutionLookUp`, which lives in `:domain:query`
 * (W8). Carrying the field as a nullable opaque type keeps
 * [MetricFlowQuerySpec] usable from W7b without pulling in the W8 dependency.
 *
 * When W8 lands, the actual `FilterSpecResolutionLookUp` type will replace
 * this and the nullable slot can either become non-null with an EMPTY default
 * or stay nullable depending on how the query parser is wired.
 */
interface FilterSpecResolutionLookupPlaceholder

/**
 * Tracks the order in which group-by items and metrics were supplied in the
 * query input.
 *
 * Port of `metricflow_semantics.specs.query_spec.InputSpecOrder`.
 *
 * The query parser uses this so the final SQL projects columns in user
 * order. Duplicates across the union of both lists indicate a programmer
 * bug — we mirror Python's `MetricFlowInternalError` by failing in `init`.
 */
data class InputSpecOrder(
    val groupByItemSpecs: List<InstanceSpec>,
    val metricSpecs: List<MetricSpec>,
) {
    init {
        val all: List<InstanceSpec> = groupByItemSpecs + metricSpecs
        val counts = LinkedHashMap<InstanceSpec, Int>()
        for (spec in all) counts[spec] = (counts[spec] ?: 0) + 1
        val duplicates = counts.filterValues { it > 1 }
        check(duplicates.isEmpty()) {
            "Duplicate specs found in the order: $duplicates (input_spec_order=$this)"
        }
    }

    companion object {
        /** Empty input order — used when callers don't care about column order. */
        val EMPTY: InputSpecOrder = InputSpecOrder(emptyList(), emptyList())
    }
}
