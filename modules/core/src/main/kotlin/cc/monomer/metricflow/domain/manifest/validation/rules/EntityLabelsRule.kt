package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Asserts that entities of the same name across semantic models share a label (or all leave
 * it null).
 *
 * Port of `metricflow_semantic_interfaces/validations/labels.py::EntityLabelsRule`.
 */
object EntityLabelsRule : SemanticManifestValidationRule {
    private data class EntityInfo(val semanticModelName: String, val label: String)

    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val entityLabelMap = mutableMapOf<String, EntityInfo>()
        for (semanticModel in semanticManifest.semanticModels) {
            for (entity in semanticModel.entities) {
                val label = entity.label ?: continue
                val existing = entityLabelMap[entity.name]
                if (existing == null) {
                    entityLabelMap[entity.name] = EntityInfo(semanticModel.name, label)
                } else if (existing.label != label) {
                    issues.add(
                        ValidationError(
                            context = FileContext.fromMetadata(semanticModel.metadata),
                            message = "Entities with the same name must have the same label or the label must be " +
                                "`None`. Entity `${entity.name}` on semantic model `${semanticModel.name}` has label " +
                                "`$label` but the same entity on semantic model " +
                                "`${existing.semanticModelName}`",
                        ),
                    )
                }
            }
        }
        return issues
    }
}
