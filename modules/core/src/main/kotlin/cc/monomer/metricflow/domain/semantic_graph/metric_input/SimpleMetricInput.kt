package cc.monomer.metricflow.domain.semantic_graph.metric_input

import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId

/**
 * Captures everything needed to construct a simple metric.
 *
 * Port of `metricflow_semantics/model/semantics/simple_metric_input.py::SimpleMetricInput`.
 *
 * Python documents this class as "all relevant values from the semantic
 * manifest for a simple metric. i.e. use this instead of fetching the `Metric`
 * from the manifest." The fields mirror the Pydantic class one-for-one;
 * grouping is by the
 * [SimpleMetricModelObjectLookup][cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup]
 * during construction.
 */
data class SimpleMetricInput(
    val name: String,
    val agg: AggregationType,
    val expr: String,
    val aggParams: SimpleMetricInputAggregation?,
    val nonAdditiveDimension: SimpleMetricInputNonAdditiveDimension?,
    val aggTimeDimensionName: String,
    val aggTimeDimensionGrain: TimeGranularity,
    val modelId: SemanticModelId,
    val joinToTimespine: Boolean,
    val fillNullsWith: Int?,
    /** The filter declared in the simple metric definition. */
    val filter: WhereFilterIntersection,
) {

    val metricReference: MetricReference get() = MetricReference(name)
}
