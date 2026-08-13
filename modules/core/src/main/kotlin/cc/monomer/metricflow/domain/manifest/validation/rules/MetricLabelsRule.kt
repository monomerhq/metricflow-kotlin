package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Asserts that metric labels are unique within a manifest.
 *
 * Port of `metricflow_semantic_interfaces/validations/labels.py::MetricLabelsRule`.
 */
object MetricLabelsRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val labelsToMetrics = mutableMapOf<String, String>()
        for (metric in semanticManifest.metrics) {
            val label = metric.label ?: continue
            val existing = labelsToMetrics[label]
            if (existing != null) {
                issues.add(
                    ValidationError(
                        context = FileContext.fromMetadata(metric.metadata),
                        message = "Can't use label `$label` for  metric `${metric.name}` " +
                            "as it's already used for metric `$existing`",
                    ),
                )
            } else {
                labelsToMetrics[label] = metric.name
            }
        }
        return issues
    }
}
