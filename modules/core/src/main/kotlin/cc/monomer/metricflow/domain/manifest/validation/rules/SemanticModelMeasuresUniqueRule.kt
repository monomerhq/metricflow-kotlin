package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Asserts that all measure names are unique across the manifest (no two semantic models can
 * declare a measure with the same name).
 *
 * Port of `metricflow_semantic_interfaces/validations/measures.py::SemanticModelMeasuresUniqueRule`.
 */
object SemanticModelMeasuresUniqueRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val measureRefToModels = mutableMapOf<MeasureReference, MutableList<String>>()

        for (semanticModel in semanticManifest.semanticModels) {
            for (measure in semanticModel.measures) {
                val existing = measureRefToModels[measure.reference]
                if (existing != null) {
                    issues.add(
                        ValidationError(
                            context = SemanticModelElementContext(
                                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                                semanticModelElement = SemanticModelElementReference(
                                    semanticModelName = semanticModel.name,
                                    elementName = measure.name,
                                ),
                                elementType = SemanticModelElementType.MEASURE,
                            ),
                            message = "Found measure with name ${measure.name} in multiple semantic models with names " +
                                "($existing)",
                        ),
                    )
                }
                measureRefToModels.getOrPut(measure.reference) { mutableListOf() }.add(semanticModel.name)
            }
        }
        return issues
    }
}
