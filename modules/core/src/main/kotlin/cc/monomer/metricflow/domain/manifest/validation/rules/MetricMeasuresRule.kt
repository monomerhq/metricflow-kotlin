package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that every measure referenced by a metric exists somewhere in the manifest.
 *
 * Port of `metricflow_semantic_interfaces/validations/measures.py::MetricMeasuresRule`.
 */
object MetricMeasuresRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val validMeasureNames = semanticManifest.semanticModels
            .flatMap { it.measures }
            .map { it.reference.elementName }
            .toSet()

        for (metric in semanticManifest.metrics) {
            for (measureRef in metric.measureReferences) {
                if (measureRef.elementName !in validMeasureNames) {
                    issues.add(
                        ValidationError(
                            context = MetricContext(
                                fileContext = FileContext.fromMetadata(metric.metadata),
                                metric = MetricModelReference(metricName = metric.name),
                            ),
                            message = "Measure ${measureRef.elementName} referenced in metric ${metric.name} is not " +
                                "defined in the model!",
                        ),
                    )
                }
            }
        }
        return issues
    }
}
