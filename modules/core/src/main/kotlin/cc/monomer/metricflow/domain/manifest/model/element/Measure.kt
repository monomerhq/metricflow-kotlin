package cc.monomer.metricflow.domain.manifest.model.element

import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import kotlinx.serialization.Serializable

/**
 * An aggregatable column declaration inside a semantic model.
 *
 * Port of `metricflow_semantic_interfaces/implementations/elements/measure.py::PydanticMeasure`.
 *
 * Pydantic's `non_additive_dimension`, `agg_time_dimension`, `label`, `config`, and several other
 * fields default to `None`; we keep that semantics by defaulting them to `null` so JSON manifests
 * which omit those keys still parse. CLAUDE.md exception (a) applies: nullable fields where Pydantic
 * was unconditionally `Optional[...]` map to a `null` default.
 */
@Serializable
data class Measure(
    val name: String,
    val agg: AggregationType,
    val description: String? = null,
    val createMetric: Boolean? = null,
    val expr: String? = null,
    val aggParams: MeasureAggregationParameters? = null,
    val metadata: Metadata? = null,
    val nonAdditiveDimension: NonAdditiveDimensionParameters? = null,
    val aggTimeDimension: String? = null,
    val label: String? = null,
    val config: SemanticLayerElementConfig? = null,
) {
    val reference: MeasureReference get() = MeasureReference(name)
}

/**
 * Aggregation-specific extra parameters (percentile etc.).
 *
 * Port of `PydanticMeasureAggregationParameters`.
 */
@Serializable
data class MeasureAggregationParameters(
    val percentile: Double? = null,
    val useDiscretePercentile: Boolean = false,
    val useApproximatePercentile: Boolean = false,
)

/**
 * Specifies a non-additive dimension for a measure — the dimension along which the measure
 * is NOT additive (e.g. snapshot balances along `metric_time`).
 *
 * Currently, only time dimensions are supported.
 *
 * Port of `PydanticNonAdditiveDimensionParameters`.
 */
@Serializable
data class NonAdditiveDimensionParameters(
    val name: String,
    val windowChoice: AggregationType = AggregationType.MIN,
    val windowGroupings: List<String> = emptyList(),
)
