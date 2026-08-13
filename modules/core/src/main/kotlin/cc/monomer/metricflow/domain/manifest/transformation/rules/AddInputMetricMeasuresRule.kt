package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.ModelTransformError
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Populates `metric.type_params.input_measures` for every metric whose input list is empty,
 * by recursively collecting the measures referenced by each metric's input metrics.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/add_input_metric_measures.py::AddInputMetricMeasuresRule`.
 *
 * - SIMPLE / CUMULATIVE metrics: pick up `type_params.measure` directly if set.
 * - DERIVED / RATIO metrics: recurse through `type_params.metrics` (and numerator/denominator)
 *   and aggregate.
 * - CONVERSION metrics: pick up `base_measure` and `conversion_measure`.
 *
 * Throws [ModelTransformError] when an input metric references a name that does not exist in
 * the manifest. Python uses a `Set` so iteration order is non-deterministic; we materialise the
 * result via a `LinkedHashSet` so the output is stable and parity-comparable.
 *
 * Note on parity: Python returns the measures as a `set`, then `list(measures)`. Set ordering in
 * CPython 3.7+ is insertion order for `dict` but **not** for `set`. To remain bug-compatible we
 * preserve "first-seen" ordering by walking the input metrics depth-first, which is also the
 * implicit Python order for the cases that actually appear in the corpus.
 */
object AddInputMetricMeasuresRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val metricsByName: Map<String, Metric> = semanticManifest.metrics.associateBy { it.name }
        val newMetrics = semanticManifest.metrics.map { metric ->
            if (metric.typeParams.inputMeasures.isNotEmpty()) return@map metric
            val collected = LinkedHashSet<MetricInputMeasure>()
            collectMeasuresForMetric(metric.name, metricsByName, collected)
            metric.copy(typeParams = metric.typeParams.copy(inputMeasures = collected.toList()))
        }
        return semanticManifest.copy(metrics = newMetrics)
    }

    private fun collectMeasuresForMetric(
        metricName: String,
        metricsByName: Map<String, Metric>,
        out: LinkedHashSet<MetricInputMeasure>,
    ) {
        val metric = metricsByName[metricName]
            ?: throw ModelTransformError("Metric '$metricName' is not configured as a metric in the model.")
        when (metric.type) {
            MetricType.SIMPLE, MetricType.CUMULATIVE -> {
                metric.typeParams.measure?.let { out.add(it) }
            }
            MetricType.DERIVED, MetricType.RATIO -> {
                for (input in metric.inputMetrics) {
                    collectMeasuresForMetric(input.name, metricsByName, out)
                }
            }
            MetricType.CONVERSION -> {
                val params = metric.typeParams.conversionTypeParams
                    ?: throw ModelTransformError("Conversion metric '${metric.name}' must have conversion_type_params.")
                params.baseMeasure?.let { out.add(it) }
                params.conversionMeasure?.let { out.add(it) }
            }
        }
    }
}
