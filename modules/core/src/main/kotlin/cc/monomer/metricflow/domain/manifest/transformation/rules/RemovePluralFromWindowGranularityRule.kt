package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Strips a stray trailing `s` from `MetricTimeWindow.granularity` (e.g. `"3 days"` -> `"day"`) when
 * the de-pluralised form matches either a built-in [TimeGranularity] or a custom granularity
 * declared in any project time spine.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/remove_plural_from_window_granularity.py`.
 *
 * Reason: YAML parsing happens before custom granularities are known, so it can't normalise the
 * trailing `s` itself. This rule does the normalisation once the full manifest is in hand. Walks
 * cumulative-metric windows, conversion-metric windows, and derived/ratio metric input offset
 * windows; SIMPLE metrics have no windows to trim.
 */
object RemovePluralFromWindowGranularityRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val validGrains: Set<String> = buildSet {
            TimeGranularity.entries.forEach { add(it.value.lowercase()) }
            semanticManifest.projectConfiguration.timeSpines.forEach { ts ->
                ts.customGranularities.forEach { add(it.name.lowercase()) }
            }
        }

        val newMetrics = semanticManifest.metrics.map { metric ->
            updateMetric(metric, validGrains)
        }
        return semanticManifest.copy(metrics = newMetrics)
    }

    private fun trimTrailingS(window: MetricTimeWindow, validGrains: Set<String>): MetricTimeWindow {
        val g = window.granularity
        if (g.endsWith("s") && g.dropLast(1) in validGrains) {
            return window.copy(granularity = g.dropLast(1))
        }
        return window
    }

    private fun updateMetric(metric: Metric, validGrains: Set<String>): Metric {
        return when (metric.type) {
            MetricType.CUMULATIVE -> {
                val ctp = metric.typeParams.cumulativeTypeParams
                val w = ctp?.window
                if (ctp != null && w != null) {
                    val newCtp = ctp.copy(window = trimTrailingS(w, validGrains))
                    metric.copy(typeParams = metric.typeParams.copy(cumulativeTypeParams = newCtp))
                } else metric
            }
            MetricType.CONVERSION -> {
                val ctp = metric.typeParams.conversionTypeParams
                val w = ctp?.window
                if (ctp != null && w != null) {
                    val newCtp = ctp.copy(window = trimTrailingS(w, validGrains))
                    metric.copy(typeParams = metric.typeParams.copy(conversionTypeParams = newCtp))
                } else metric
            }
            MetricType.DERIVED, MetricType.RATIO -> {
                // Walk every input metric: numerator/denominator for RATIO, metrics list for DERIVED.
                val tp = metric.typeParams
                val newNumerator = tp.numerator?.let { input ->
                    input.offsetWindow?.let { input.copy(offsetWindow = trimTrailingS(it, validGrains)) } ?: input
                }
                val newDenominator = tp.denominator?.let { input ->
                    input.offsetWindow?.let { input.copy(offsetWindow = trimTrailingS(it, validGrains)) } ?: input
                }
                val newMetrics = tp.metrics?.map { input ->
                    input.offsetWindow?.let { input.copy(offsetWindow = trimTrailingS(it, validGrains)) } ?: input
                }
                metric.copy(
                    typeParams = tp.copy(
                        numerator = newNumerator,
                        denominator = newDenominator,
                        metrics = newMetrics,
                    ),
                )
            }
            MetricType.SIMPLE -> metric
        }
    }
}
