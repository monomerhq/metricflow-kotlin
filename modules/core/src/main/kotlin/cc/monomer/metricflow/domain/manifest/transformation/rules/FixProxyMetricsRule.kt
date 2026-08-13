package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule
import org.slf4j.LoggerFactory

/**
 * Overrides `metric.type_params.expr` on SIMPLE proxy metrics so it always matches the
 * referenced measure's expr (falling back to the measure name).
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/fix_proxy_metrics.py::FixProxyMetricsRule`.
 *
 * Background: the spec allows users to set an `expr` on a SIMPLE metric that just proxies a
 * measure, but the rendered SQL always uses the measure's expr — so the user-set expr is
 * dead weight that can subtly drift. With the measures-deprecation migration the rendered SQL
 * is starting to come from `metric.type_params.expr` instead, which means a stale user-set
 * expr could break legacy queries. This rule guarantees that for proxy metrics, `expr` is
 * always exactly the referenced measure's expr (or name).
 *
 * Note: when the SIMPLE metric has no `type_params.measure` (the new spec), this rule is a
 * no-op for that metric.
 */
object FixProxyMetricsRule : SemanticManifestTransformRule {
    private val log = LoggerFactory.getLogger(FixProxyMetricsRule::class.java)

    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val allMeasures = buildMap {
            for (model in semanticManifest.semanticModels) {
                for (measure in model.measures) {
                    put(measure.name, measure)
                }
            }
        }
        val newMetrics = semanticManifest.metrics.map { metric ->
            if (metric.type != MetricType.SIMPLE) return@map metric
            val measureRef = metric.typeParams.measure ?: return@map metric
            val referenced = allMeasures[measureRef.name]
            if (referenced == null) {
                log.warn("Measure {} not found", measureRef.name)
                return@map metric
            }
            if (metric.typeParams.expr != null &&
                metric.typeParams.expr != referenced.expr &&
                metric.typeParams.expr != referenced.name
            ) {
                log.warn(
                    "Metric {} should not have an expr set if it's proxy from measures, overriding with measure",
                    metric.name,
                )
            }
            metric.copy(
                typeParams = metric.typeParams.copy(expr = referenced.expr ?: referenced.name),
            )
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}
