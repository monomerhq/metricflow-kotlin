package cc.monomer.metricflow.domain.semantic_graph.metric_input

import cc.monomer.metricflow.domain.manifest.model.element.NonAdditiveDimensionParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType

/**
 * Non-additive dimension carried on a [SimpleMetricInput].
 *
 * Port of `metricflow_semantics/model/semantics/simple_metric_input.py::SimpleMetricInputNonAdditiveDimension`.
 *
 * Describes the dimension along which a measure is NOT additive (e.g. snapshot
 * balances along `metric_time`). The [windowChoice] selects the aggregation
 * applied along that dimension (typically `MIN` / `MAX`) and [windowGroupings]
 * names the other dimensions to keep as group-by keys when applying the
 * windowed aggregation.
 */
data class SimpleMetricInputNonAdditiveDimension(
    val name: String,
    val windowChoice: AggregationType,
    val windowGroupings: List<String>,
) {
    companion object {
        /** Factory matching Python's `create_from_non_additive_dimension`. */
        fun createFromManifest(
            nonAdditiveDimension: NonAdditiveDimensionParameters?,
        ): SimpleMetricInputNonAdditiveDimension? {
            if (nonAdditiveDimension == null) return null
            return SimpleMetricInputNonAdditiveDimension(
                name = nonAdditiveDimension.name,
                windowChoice = nonAdditiveDimension.windowChoice,
                windowGroupings = nonAdditiveDimension.windowGroupings.toList(),
            )
        }
    }
}
