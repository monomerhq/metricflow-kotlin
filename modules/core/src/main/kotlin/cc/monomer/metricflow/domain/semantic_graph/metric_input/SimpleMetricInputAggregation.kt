package cc.monomer.metricflow.domain.semantic_graph.metric_input

import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters

/**
 * Aggregation parameters carried on a [SimpleMetricInput].
 *
 * Port of `metricflow_semantics/model/semantics/simple_metric_input.py::SimpleMetricInputAggregation`.
 *
 * Optional percentile parameters for the corresponding measure-style
 * aggregation. Aligned with Python's optional fields: [percentile] may be
 * `null` even when the discrete / approximate flags are set, mirroring the
 * Pydantic shape.
 */
data class SimpleMetricInputAggregation(
    val percentile: Double?,
    val useDiscretePercentile: Boolean,
    val useApproximatePercentile: Boolean,
) {
    companion object {
        /**
         * Factory matching Python's `create_from_pydantic`. Returns `null` when
         * the source object is itself `null` so callers can flow `null` through
         * directly.
         */
        fun createFromManifest(aggregation: MeasureAggregationParameters?): SimpleMetricInputAggregation? {
            if (aggregation == null) return null
            return SimpleMetricInputAggregation(
                percentile = aggregation.percentile,
                useDiscretePercentile = aggregation.useDiscretePercentile,
                useApproximatePercentile = aggregation.useApproximatePercentile,
            )
        }
    }
}
