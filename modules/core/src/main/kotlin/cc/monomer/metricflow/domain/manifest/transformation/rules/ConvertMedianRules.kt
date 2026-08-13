package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.ModelTransformError
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

private const val MEDIAN_PERCENTILE: Double = 0.5

private fun throwConflictingPercentile(objectName: String, objectType: String, percentile: Double): Nothing =
    throw ModelTransformError(
        "$objectType '$objectName' uses a MEDIAN aggregation, while percentile " +
            "is set to '$percentile', a conflicting value. Please remove the parameter " +
            "or set to '0.5'.",
    )

private fun throwConflictingDiscretePercentile(objectName: String, objectType: String): Nothing =
    throw ModelTransformError(
        "$objectType '$objectName' uses a MEDIAN aggregation, while use_discrete_percentile " +
            "is set to true. Please remove the parameter or set to False.",
    )

/**
 * Converts SIMPLE metrics whose `metric_aggregation_params.agg == MEDIAN` into `PERCENTILE`
 * with a 0.5 percentile.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/convert_median.py::ConvertMedianMetricToPercentile`.
 *
 * Rejects metrics that already have a non-0.5 percentile or `use_discrete_percentile=true`.
 */
object ConvertMedianMetricToPercentile : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newMetrics = semanticManifest.metrics.map { metric ->
            val agg = metric.typeParams.metricAggregationParams
            if (metric.type == MetricType.SIMPLE && agg != null && agg.agg == AggregationType.MEDIAN) {
                val current = agg.aggParams
                val updated = if (current == null) {
                    MeasureAggregationParameters(percentile = MEDIAN_PERCENTILE)
                } else {
                    val pct = current.percentile
                    if (pct != null && pct != MEDIAN_PERCENTILE) {
                        throwConflictingPercentile(metric.name, "Metric", pct)
                    }
                    if (current.useDiscretePercentile) {
                        throwConflictingDiscretePercentile(metric.name, "Metric")
                    }
                    current.copy(percentile = MEDIAN_PERCENTILE)
                }
                val newAggParams = agg.copy(
                    agg = AggregationType.PERCENTILE,
                    aggParams = updated,
                )
                metric.copy(typeParams = metric.typeParams.copy(metricAggregationParams = newAggParams))
            } else metric
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}

/**
 * Converts legacy MEDIAN measures into PERCENTILE with a 0.5 percentile.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/convert_median.py::ConvertMedianToPercentileRule`.
 *
 * Rejects measures that already have a non-0.5 percentile or `use_discrete_percentile=true`.
 * `use_approximate_percentile` is intentionally preserved (valid for performance reasons).
 */
object ConvertMedianToPercentileRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newModels = semanticManifest.semanticModels.map { model ->
            val newMeasures = model.measures.map { measure ->
                if (measure.agg == AggregationType.MEDIAN) {
                    val current = measure.aggParams
                    val updated = if (current == null) {
                        MeasureAggregationParameters(percentile = MEDIAN_PERCENTILE)
                    } else {
                        val pct = current.percentile
                        if (pct != null && pct != MEDIAN_PERCENTILE) {
                            throwConflictingPercentile(measure.name, "Measure", pct)
                        }
                        if (current.useDiscretePercentile) {
                            throwConflictingDiscretePercentile(measure.name, "Measure")
                        }
                        current.copy(percentile = MEDIAN_PERCENTILE)
                    }
                    measure.copy(
                        agg = AggregationType.PERCENTILE,
                        aggParams = updated,
                    )
                } else measure
            }
            model.copy(measures = newMeasures)
        }
        return semanticManifest.copy(semanticModels = newModels)
    }
}
