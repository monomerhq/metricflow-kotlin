package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.ModelTransformError
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Token used in the metricflow port for "count all rows" (`expr: 1`). Used by both COUNT
 * conversion rules to detect the canonical "count(*)-equivalent" expression and skip the
 * `CASE WHEN ... IS NOT NULL` rewrite.
 *
 * Mirrors `ONE` in `metricflow_semantic_interfaces/transformations/convert_count.py`.
 */
private const val COUNT_ONE_SENTINEL: String = "1"

private fun maybeTransformCountExpression(expr: String): String =
    if (expr == COUNT_ONE_SENTINEL) expr else "CASE WHEN $expr IS NOT NULL THEN 1 ELSE 0 END"

private fun throwMissingCountExpr(objectName: String, objectType: String): Nothing =
    throw ModelTransformError(
        "$objectType '$objectName' uses a COUNT aggregation, which requires an expr to be " +
            "provided. Provide 'expr: 1' if a count of all rows is desired.",
    )

/**
 * Converts SIMPLE metrics whose `metric_aggregation_params.agg == COUNT` into `SUM` over a
 * `CASE WHEN <expr> IS NOT NULL THEN 1 ELSE 0 END` rewrite (or leaves `expr: 1` untouched).
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/convert_count.py::ConvertCountMetricToSumRule`.
 *
 * Throws [ModelTransformError] if the metric has no `expr` set; the user is expected to set
 * `expr: 1` to count all rows.
 */
object ConvertCountMetricToSumRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newMetrics = semanticManifest.metrics.map { metric ->
            val agg = metric.typeParams.metricAggregationParams
            if (metric.type == MetricType.SIMPLE && agg != null && agg.agg == AggregationType.COUNT) {
                val expr = metric.typeParams.expr
                    ?: throwMissingCountExpr(objectName = metric.name, objectType = "Metric")
                val newAggParams = agg.copy(agg = AggregationType.SUM)
                val newTypeParams = metric.typeParams.copy(
                    expr = maybeTransformCountExpression(expr),
                    metricAggregationParams = newAggParams,
                )
                metric.copy(typeParams = newTypeParams)
            } else metric
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}

/**
 * Converts legacy COUNT measures into SUM via a `CASE WHEN <expr> IS NOT NULL THEN 1 ELSE 0 END`
 * rewrite (or leaves `expr: 1` untouched).
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/convert_count.py::ConvertCountToSumRule`.
 *
 * Legacy behaviour — will be irrelevant once measures are no longer supported. Throws
 * [ModelTransformError] if a COUNT measure has no `expr`. Note: the Python error message
 * mis-labels measures as "Metric" — we preserve that wording for parity.
 */
object ConvertCountToSumRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newModels = semanticManifest.semanticModels.map { model ->
            val newMeasures = model.measures.map { measure ->
                if (measure.agg == AggregationType.COUNT) {
                    val expr = measure.expr
                        // Python's error message uses "Metric" here (a known bug we mirror).
                        ?: throwMissingCountExpr(objectName = measure.name, objectType = "Metric")
                    measure.copy(
                        expr = maybeTransformCountExpression(expr),
                        agg = AggregationType.SUM,
                    )
                } else measure
            }
            model.copy(measures = newMeasures)
        }
        return semanticManifest.copy(semanticModels = newModels)
    }
}
