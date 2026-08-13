package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Converts simple metrics whose `metric_aggregation_params.agg == SUM_BOOLEAN` into `SUM`
 * by wrapping the metric's `expr` in a `CASE WHEN <expr> THEN 1 ELSE 0 END`.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/boolean_aggregations.py::BooleanAggregationRule`.
 *
 * Only applies to SIMPLE metrics that aggregate an expression directly via
 * `type_params.metric_aggregation_params`. Metrics that rely on a measure input are handled by
 * [BooleanMeasureAggregationRule].
 */
object BooleanAggregationRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newMetrics = semanticManifest.metrics.map { metric ->
            val agg = metric.typeParams.metricAggregationParams
            if (metric.type == MetricType.SIMPLE && agg != null && agg.agg == AggregationType.SUM_BOOLEAN) {
                val newAggParams = agg.copy(agg = AggregationType.SUM)
                val newTypeParams = metric.typeParams.copy(
                    expr = buildBooleanCaseExpr(metric.name, metric.typeParams.expr),
                    metricAggregationParams = newAggParams,
                )
                metric.copy(typeParams = newTypeParams)
            } else metric
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}
