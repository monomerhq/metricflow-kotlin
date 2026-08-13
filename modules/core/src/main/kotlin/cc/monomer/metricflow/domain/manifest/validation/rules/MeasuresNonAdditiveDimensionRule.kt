package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SharedMeasureAndMetricHelpers
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Validates a measure's `non_additive_dimension` declaration: the named dimension must exist,
 * must be a TIME dimension, must share granularity with the agg_time_dimension, and the
 * `window_choice` / `window_groupings` must be valid.
 *
 * Port of `metricflow_semantic_interfaces/validations/measures.py::MeasuresNonAdditiveDimensionRule`.
 */
object MeasuresNonAdditiveDimensionRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            for (measure in semanticModel.measures) {
                val nad = measure.nonAdditiveDimension ?: continue
                val aggTimeDimRef = try {
                    semanticModel.checkedAggTimeDimensionForMeasure(measure.reference)
                } catch (_: IllegalStateException) {
                    continue
                } catch (_: IllegalArgumentException) {
                    continue
                }
                issues.addAll(
                    SharedMeasureAndMetricHelpers.validateNonAdditiveDimension(
                        objectName = measure.name,
                        semanticModel = semanticModel,
                        nonAdditiveDimension = nad,
                        aggTimeDimensionReference = aggTimeDimRef,
                        objectTypeForErrors = "Measure",
                    ),
                )
            }
        }
        return issues
    }
}
