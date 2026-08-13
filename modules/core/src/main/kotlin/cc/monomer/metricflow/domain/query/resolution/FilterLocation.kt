package cc.monomer.metricflow.domain.query.resolution

import cc.monomer.metricflow.domain.manifest.model.references.MetricReference

/**
 * Where in the query (and its derived metric tree) a where-filter is
 * declared.
 *
 * Port of `metricflow_semantics.query.group_by_item.filter_spec_resolution.filter_location.WhereFilterLocationType`.
 */
enum class WhereFilterLocationType(val value: String) {
    QUERY("query"),
    METRIC("metric"),
    INPUT_METRIC("input_metric"),
}

/**
 * Records the location of a where-filter for filter-spec resolution.
 *
 * Port of `WhereFilterLocation`.
 *
 * Valid group-by items depend only on the metrics in scope at the
 * filter's location, so the metric references at the location are
 * enough — the actual filter text doesn't appear here.
 *
 * The constructor enforces Python's sorted-tuple invariant on
 * [metricReferences] so two equivalent locations are structurally equal.
 */
data class WhereFilterLocation(
    val locationType: WhereFilterLocationType,
    val metricReferences: List<MetricReference>,
) {

    init {
        check(metricReferences == metricReferences.sortedBy { it.elementName }) {
            "metricReferences must be sorted by element name."
        }
    }

    companion object {
        /** Build a query-level filter location. */
        fun forQuery(metricReferences: List<MetricReference>): WhereFilterLocation = WhereFilterLocation(
            locationType = WhereFilterLocationType.QUERY,
            metricReferences = metricReferences.sortedBy { it.elementName },
        )

        /** Build a metric-level filter location. */
        fun forMetric(metricReference: MetricReference): WhereFilterLocation = WhereFilterLocation(
            locationType = WhereFilterLocationType.METRIC,
            metricReferences = listOf(metricReference),
        )

        /** Build an input-metric filter location. */
        fun forInputMetric(inputMetricReference: MetricReference): WhereFilterLocation = WhereFilterLocation(
            locationType = WhereFilterLocationType.INPUT_METRIC,
            metricReferences = listOf(inputMetricReference),
        )
    }
}
