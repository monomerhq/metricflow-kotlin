package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Helper that builds the `CASE WHEN ... THEN 1 ELSE 0 END` expression used by both measure
 * and metric boolean aggregation rules.
 *
 * Port of `BooleanAggregationRule.build_new_expr_value` in
 * `metricflow_semantic_interfaces/transformations/boolean_aggregations.py`.
 */
internal fun buildBooleanCaseExpr(name: String, expr: String?): String {
    val sub = if (expr.isNullOrEmpty()) name else expr
    return "CASE WHEN $sub THEN 1 ELSE 0 END"
}

/**
 * Converts boolean-aggregation measures (`agg: sum_boolean`) into plain `sum` aggregations
 * by wrapping the measure's `expr` in a `CASE WHEN <expr> THEN 1 ELSE 0 END`.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/boolean_aggregations.py::BooleanMeasureAggregationRule`.
 *
 * Legacy-style only: applies to measure-shaped models. The metric-shaped equivalent is
 * [BooleanAggregationRule].
 */
object BooleanMeasureAggregationRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newModels = semanticManifest.semanticModels.map { model ->
            val newMeasures = model.measures.map { measure ->
                if (measure.agg == AggregationType.SUM_BOOLEAN) {
                    measure.copy(
                        expr = buildBooleanCaseExpr(measure.name, measure.expr),
                        agg = AggregationType.SUM,
                    )
                } else measure
            }
            model.copy(measures = newMeasures)
        }
        return semanticManifest.copy(semanticModels = newModels)
    }
}
