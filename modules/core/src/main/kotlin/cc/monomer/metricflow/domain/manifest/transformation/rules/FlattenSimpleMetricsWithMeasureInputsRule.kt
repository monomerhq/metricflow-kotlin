package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule
import org.slf4j.LoggerFactory

/**
 * Flattens SIMPLE metrics that reference a measure into self-contained metrics holding the
 * measure's aggregation settings on `metric_aggregation_params`.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/flatten_simple_metrics_with_measure_inputs.py::FlattenSimpleMetricsWithMeasureInputsRule`.
 *
 * For each SIMPLE metric whose `type_params.measure` points to a known measure, this rule:
 *  - Copies the measure's `agg`, `agg_params`, `agg_time_dimension`, `non_additive_dimension`,
 *    and `expr` onto the metric.
 *  - Folds the input measure's filter into the metric's filter.
 *  - Sets `input_measures` to a one-element list with an "artificial" measure input so that
 *    downstream code still sees the measure-derived linkage.
 *
 * Metrics that already have `metric_aggregation_params` set are skipped (the flattening was
 * already performed, or the user supplied direct aggregation parameters).
 *
 * If the referenced measure does not exist anywhere in the manifest, the rule logs a warning
 * and skips the metric — validation will catch it later.
 */
object FlattenSimpleMetricsWithMeasureInputsRule : SemanticManifestTransformRule {
    private val log = LoggerFactory.getLogger(FlattenSimpleMetricsWithMeasureInputsRule::class.java)

    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val measureInfo = semanticManifest.buildMeasureNameToModelAndMeasureMap()
        val newMetrics = semanticManifest.metrics.map { metric ->
            if (metric.type != MetricType.SIMPLE) return@map metric
            val inputMeasure = metric.typeParams.measure ?: return@map metric
            val pair = measureInfo[inputMeasure.name]
            if (pair == null) {
                log.warn(
                    "Measure {} not found in any semantic model; skipping flattening of metric. " +
                        "(This should also be caught by validations.)",
                    inputMeasure.name,
                )
                return@map metric
            }
            val (semanticModel, measure) = pair
            applyMeasureFeaturesToSimpleMetric(
                measure = measure,
                semanticModelName = semanticModel.name,
                metric = metric,
                fillNullsWith = inputMeasure.fillNullsWith,
                joinToTimespine = inputMeasure.joinToTimespine,
                measureInputFilters = inputMeasure.filter,
            )
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}
