package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Asserts that semantic-model labels are unique across the manifest, and that labels on
 * sub-elements (dimensions / entities / measures) are unique within each semantic model.
 *
 * Port of `metricflow_semantic_interfaces/validations/labels.py::SemanticModelLabelsRule`.
 */
object SemanticModelLabelsRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val labelsToModels = mutableMapOf<String, String>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(checkSemanticModel(semanticModel, labelsToModels))
            issues.addAll(checkSubElementLabels(semanticModel, semanticModel.dimensions.map { it.label }, "Dimension", "dimensions"))
            issues.addAll(checkSubElementLabels(semanticModel, semanticModel.entities.map { it.label }, "Entity", "entities"))
            issues.addAll(checkSubElementLabels(semanticModel, semanticModel.measures.map { it.label }, "Measure", "measures"))
        }
        return issues
    }

    private fun checkSemanticModel(
        semanticModel: SemanticModel,
        existingLabels: MutableMap<String, String>,
    ): List<ValidationIssue> {
        val label = semanticModel.label ?: return emptyList()
        val existing = existingLabels[label]
        if (existing != null) {
            return listOf(
                ValidationError(
                    context = FileContext.fromMetadata(semanticModel.metadata),
                    message = "Can't use label `$label` for  semantic model `${semanticModel.name}` " +
                        "as it's already used for semantic model `$existing`",
                ),
            )
        }
        existingLabels[label] = semanticModel.name
        return emptyList()
    }

    private fun checkSubElementLabels(
        semanticModel: SemanticModel,
        labels: List<String?>,
        elementTypeWord: String,
        pluralWord: String,
    ): List<ValidationIssue> {
        val counts = mutableMapOf<String, Int>()
        for (l in labels) {
            if (l != null) counts.merge(l, 1) { a, b -> a + b }
        }
        return counts.filter { it.value > 1 }.map { (label, count) ->
            ValidationError(
                context = FileContext.fromMetadata(semanticModel.metadata),
                message = "$elementTypeWord labels must be unique within a semantic model. The label `$label` was " +
                    "used for $count $pluralWord on semantic model `${semanticModel.name}",
            )
        }
    }
}
