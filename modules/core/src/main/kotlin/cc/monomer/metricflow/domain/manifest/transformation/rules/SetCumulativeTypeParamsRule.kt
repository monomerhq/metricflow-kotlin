package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.CumulativeTypeParams
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule

/**
 * Backfills `metric.type_params.cumulative_type_params` from the legacy `window` / `grain_to_date`
 * fields on `type_params`, and ensures every CUMULATIVE metric has a `cumulative_type_params`
 * record (so its default `period_agg = FIRST` is materialised).
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/cumulative_type_params.py::SetCumulativeTypeParamsRule`.
 *
 * Old YAML put `window` and `grain_to_date` directly on `type_params`; the canonical shape moves
 * them into `cumulative_type_params`. This rule preserves the legacy fields in place (for
 * backward compatibility) while populating their new home if it's empty.
 */
object SetCumulativeTypeParamsRule : SemanticManifestTransformRule {
    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val newMetrics = semanticManifest.metrics.map { metric ->
            if (metric.type != MetricType.CUMULATIVE) return@map metric
            val tp = metric.typeParams
            // Ensure cumulative_type_params exists; the model default `periodAgg = FIRST`
            // is set in the data class.
            val baseCtp = tp.cumulativeTypeParams ?: CumulativeTypeParams()
            val ctpWithWindow = if (tp.window != null && baseCtp.window == null) {
                baseCtp.copy(window = tp.window)
            } else baseCtp
            val gtd = tp.grainToDate
            val ctpFinal = if (gtd != null && ctpWithWindow.grainToDate == null) {
                ctpWithWindow.copy(grainToDate = gtd.value)
            } else ctpWithWindow
            metric.copy(typeParams = tp.copy(cumulativeTypeParams = ctpFinal))
        }
        return semanticManifest.copy(metrics = newMetrics)
    }
}
