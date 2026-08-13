package cc.monomer.metricflow.domain.spec

/**
 * Represents how the measure is aggregated.
 *
 * Port of `metricflow_semantics.aggregation_properties.AggregationState`.
 *
 * The state advances as a simple metric input flows through the dataflow plan:
 * source columns are [NON_AGGREGATED], partial aggregations (e.g. constrained
 * source) are [PARTIAL], and once aggregated to the grain of the group-by
 * items the state becomes [COMPLETE].
 */
enum class AggregationState(val value: String) {
    /** When reading from the source, the measure is considered non-aggregated. */
    NON_AGGREGATED("NON_AGGREGATED"),

    /** Aggregated but not yet to the final group-by grain. */
    PARTIAL("PARTIAL"),

    /** Aggregated to the grain of the group-by-items. */
    COMPLETE("COMPLETE"),
}
