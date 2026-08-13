package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that the manifest defines at least one semantic model and at least one metric (or
 * has a measure with `create_metric: true` so that the transformer will synthesize one).
 *
 * Port of `metricflow_semantic_interfaces/validations/non_empty.py::NonEmptyRule`.
 */
object NonEmptyRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        if (semanticManifest.semanticModels.isEmpty()) {
            issues.add(ValidationError(message = "No semantic models present in the model."))
        }
        val hasCreateMetric = semanticManifest.semanticModels
            .any { sm -> sm.measures.any { it.createMetric == true } }
        if (semanticManifest.metrics.isEmpty() && !hasCreateMetric) {
            issues.add(ValidationError(message = "No metrics present in the model."))
        }
        return issues
    }
}
