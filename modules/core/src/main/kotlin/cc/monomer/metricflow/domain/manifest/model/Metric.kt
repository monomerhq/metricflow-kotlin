package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters
import cc.monomer.metricflow.domain.manifest.model.element.NonAdditiveDimensionParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.ConversionCalculationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.PeriodAggregation
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import kotlinx.serialization.Serializable

/**
 * Describes a metric.
 *
 * Port of `metricflow_semantic_interfaces/implementations/metric.py::PydanticMetric`.
 *
 * Metric type ([MetricType]) determines which fields inside [typeParams] are populated. We keep
 * the Python shape — one record with a discriminator enum and a wide [MetricTypeParams] holding
 * all variant payloads — rather than promoting to a Kotlin sealed hierarchy, because the JSON
 * is shaped that way (`type_params` is always present, with the non-applicable sub-fields set
 * to null).
 */
@Serializable
data class Metric(
    val name: String,
    val description: String? = null,
    val type: MetricType,
    val typeParams: MetricTypeParams,
    val filter: WhereFilterIntersection? = null,
    val metadata: Metadata? = null,
    val label: String? = null,
    val config: SemanticLayerElementConfig? = null,
    val timeGranularity: String? = null,
) {

    /** Convenience accessor: the list of input measures for this metric (mirrors `typeParams.inputMeasures`). */
    val inputMeasures: List<MetricInputMeasure>
        get() = typeParams.inputMeasures

    /** All measure references associated with all input measure configurations. */
    val measureReferences: List<MeasureReference>
        get() = inputMeasures.map { it.measureReference }

    /** The input metrics this metric depends on (for DERIVED / RATIO / CONVERSION). */
    val inputMetrics: List<MetricInput>
        get() = when (type) {
            MetricType.SIMPLE, MetricType.CUMULATIVE -> emptyList()
            MetricType.DERIVED -> typeParams.metrics ?: emptyList()
            MetricType.RATIO -> {
                val n = typeParams.numerator
                val d = typeParams.denominator
                check(n != null && d != null) {
                    "$this is metric type ${MetricType.RATIO}, neither numerator nor denominator should be null"
                }
                listOf(n, d)
            }
            MetricType.CONVERSION -> {
                val params = typeParams.conversionTypeParams
                checkNotNull(params) { "Conversion metric '$name' must have conversion_type_params." }
                buildList {
                    params.baseMetric?.let { add(it) }
                    params.conversionMetric?.let { add(it) }
                }
            }
        }
}

/**
 * The wide payload holding parameters for every supported metric type.
 *
 * Port of `PydanticMetricTypeParams`. Different metric types use different fields:
 * - SIMPLE uses [measure] (legacy) or [metricAggregationParams] (newer), with [joinToTimespine] / [fillNullsWith].
 * - RATIO uses [numerator] and [denominator].
 * - DERIVED uses [expr] and [metrics].
 * - CUMULATIVE uses [cumulativeTypeParams] (newer) and the legacy [window] / [grainToDate].
 * - CONVERSION uses [conversionTypeParams].
 */
@Serializable
data class MetricTypeParams(
    val measure: MetricInputMeasure? = null,
    val numerator: MetricInput? = null,
    val denominator: MetricInput? = null,
    val expr: String? = null,
    /** Legacy field, retained because corpus manifests still emit it for cumulative metrics. */
    val window: MetricTimeWindow? = null,
    /** Legacy field, does not support custom granularity. */
    val grainToDate: TimeGranularity? = null,
    val metrics: List<MetricInput>? = null,
    val conversionTypeParams: ConversionTypeParams? = null,
    val cumulativeTypeParams: CumulativeTypeParams? = null,
    val inputMeasures: List<MetricInputMeasure> = emptyList(),
    val metricAggregationParams: MetricAggregationParams? = null,
    val joinToTimespine: Boolean = false,
    val fillNullsWith: Int? = null,
    val isPrivate: Boolean? = false,
)

/**
 * Pointer to a measure with metric-specific processing directives.
 *
 * Port of `PydanticMetricInputMeasure`.
 */
@Serializable
data class MetricInputMeasure(
    val name: String,
    val filter: WhereFilterIntersection? = null,
    val alias: String? = null,
    val joinToTimespine: Boolean = false,
    val fillNullsWith: Int? = null,
) {
    val measureReference: MeasureReference get() = MeasureReference(name)
    val postAggregationMeasureReference: MeasureReference get() = MeasureReference(alias ?: name)
}

/**
 * Window of time over which a metric is accumulated, e.g. `1 day`, `2 weeks`.
 *
 * Port of `PydanticMetricTimeWindow`.
 */
@Serializable
data class MetricTimeWindow(
    val count: Int,
    val granularity: String,
) {
    /** Whether the granularity matches a standard [TimeGranularity] (vs a custom user-defined grain). */
    val isStandardGranularity: Boolean
        get() = TimeGranularity.entries.any { it.value.equals(granularity, ignoreCase = true) }

    /** `"<count> <granularity>"` — the canonical string form. */
    val windowString: String get() = "$count $granularity"

    companion object {
        /** Parse a string like `"1 day"` / `"28 days"` into a window. Casing is ignored. */
        fun parse(window: String): MetricTimeWindow {
            val parts = window.lowercase().split(" ")
            if (parts.size != 2) {
                throw ParsingException(
                    "Invalid window ($window) in cumulative metric. Should be of the form " +
                        "`<count> <granularity>`, e.g., `28 days`",
                )
            }
            val count = parts[0]
            val granularity = parts[1]
            if (!count.all { it.isDigit() }) {
                throw ParsingException("Invalid count ($count) in cumulative metric window string: ($window)")
            }
            return MetricTimeWindow(count = count.toInt(), granularity = granularity)
        }
    }
}

/**
 * Pointer to a metric with optional offset/alias/filter directives.
 *
 * Port of `PydanticMetricInput`.
 */
@Serializable
data class MetricInput(
    val name: String,
    val filter: WhereFilterIntersection? = null,
    val alias: String? = null,
    val offsetWindow: MetricTimeWindow? = null,
    val offsetToGrain: String? = null,
) {
    val asReference: MetricReference get() = MetricReference(name)
    val postAggregationReference: MetricReference get() = MetricReference(alias ?: name)
}

/**
 * Property pair used in conversion metrics — base and conversion sides of the same logical
 * property (e.g. `user__country` on both sides).
 *
 * Port of `PydanticConstantPropertyInput`.
 */
@Serializable
data class ConstantPropertyInput(
    val baseProperty: String,
    val conversionProperty: String,
)

/**
 * Type params for conversion metrics.
 *
 * Port of `PydanticConversionTypeParams`.
 */
@Serializable
data class ConversionTypeParams(
    val baseMeasure: MetricInputMeasure? = null,
    val baseMetric: MetricInput? = null,
    val conversionMeasure: MetricInputMeasure? = null,
    val conversionMetric: MetricInput? = null,
    val entity: String,
    val calculation: ConversionCalculationType = ConversionCalculationType.CONVERSION_RATE,
    val window: MetricTimeWindow? = null,
    val constantProperties: List<ConstantPropertyInput>? = null,
)

/**
 * Type params for cumulative metrics.
 *
 * Port of `PydanticCumulativeTypeParams`.
 */
@Serializable
data class CumulativeTypeParams(
    val window: MetricTimeWindow? = null,
    val grainToDate: String? = null,
    val periodAgg: PeriodAggregation = PeriodAggregation.FIRST,
    val metric: MetricInput? = null,
)

/**
 * Aggregation parameters that propagated from a measure onto a metric used as a source node.
 *
 * Port of `PydanticMetricAggregationParams`.
 *
 * If you add fields here, please update the transformation helper that creates this from a
 * measure (`PydanticMeasure.to_metric_aggregation_params()` in Python).
 */
@Serializable
data class MetricAggregationParams(
    val semanticModel: String,
    val agg: AggregationType,
    val aggParams: MeasureAggregationParameters? = null,
    val aggTimeDimension: String? = null,
    val nonAdditiveDimension: NonAdditiveDimensionParameters? = null,
)
