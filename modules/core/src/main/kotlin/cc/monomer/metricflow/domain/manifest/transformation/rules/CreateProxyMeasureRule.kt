package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.ModelTransformError
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule
import org.slf4j.LoggerFactory

/**
 * For every measure declared with `create_metric: true`, adds a SIMPLE proxy metric of the same
 * name if no such metric already exists. Rejects manifests where a non-SIMPLE metric already
 * shadows the measure name.
 *
 * Port of `metricflow_semantic_interfaces/transformations/proxy_measure.py::CreateProxyMeasureRule`.
 *
 * Always runs **first** in the secondary `convert_legacy_measures_to_metrics_rules` block —
 * later rules (`AddInputMetricMeasuresRule`, `FlattenSimpleMetricsWithMeasureInputsRule`,
 * `ReplaceInputMeasuresWithSimpleMetricsTransformationRule`, `FixProxyMetricsRule`) all assume
 * the proxy metrics are in place.
 */
object CreateProxyMeasureRule : SemanticManifestTransformRule {
    private val log = LoggerFactory.getLogger(CreateProxyMeasureRule::class.java)

    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val metricsAcc = semanticManifest.metrics.toMutableList()
        // We mutate this list deliberately: Python appends each new proxy metric to the manifest
        // and the inner `for metric in semantic_manifest.metrics` loop sees them on later iterations
        // — that's important because a measure-with-create_metric=true that names an existing
        // proxy metric is supposed to skip without error.
        for (model in semanticManifest.semanticModels) {
            for (measure in model.measures) {
                if (measure.createMetric != true) continue
                var addMetric = true
                for (existing in metricsAcc) {
                    if (existing.name == measure.name) {
                        if (existing.type != MetricType.SIMPLE) {
                            throw ModelTransformError(
                                "Cannot have metric with the same name as a measure (${measure.name}) that is not a " +
                                    "created mechanically from that measure using create_metric=True",
                            )
                        }
                        log.warn(
                            "Metric already exists with name ({}). *Not* adding measure proxy metric for that measure",
                            measure.name,
                        )
                        addMetric = false
                    }
                }
                if (addMetric) {
                    val built = buildMetricFromMeasureConfiguration(
                        measure = measure,
                        semanticModelName = model.name,
                        fillNullsWith = null,
                        joinToTimespine = false,
                        // we override the default here; this metric was explicitly created by the user.
                        isPrivate = false,
                        measureInputFilters = null,
                    )
                    // Python overrides name + type_params.measure after the build call.
                    val proxyMetric = built.copy(
                        name = measure.name,
                        typeParams = built.typeParams.copy(
                            measure = MetricInputMeasure(name = measure.name),
                        ),
                    )
                    metricsAcc.add(proxyMetric)
                }
            }
        }
        return semanticManifest.copy(metrics = metricsAcc.toList())
    }
}
