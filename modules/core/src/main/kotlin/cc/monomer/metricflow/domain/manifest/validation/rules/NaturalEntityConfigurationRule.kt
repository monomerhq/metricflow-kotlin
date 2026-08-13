package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Ensures entities marked [EntityType.NATURAL] are configured correctly: at most one per
 * semantic model, and only used with semantic models that define a validity window.
 *
 * Port of `metricflow_semantic_interfaces/validations/entities.py::NaturalEntityConfigurationRule`.
 */
object NaturalEntityConfigurationRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(validateSemanticModel(semanticModel))
        }
        return issues
    }

    private fun validateSemanticModel(semanticModel: SemanticModel): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val context = SemanticModelContext(
            fileContext = FileContext.fromMetadata(semanticModel.metadata),
            semanticModel = SemanticModelReference(semanticModelName = semanticModel.name),
        )

        val naturalEntityNames = semanticModel.entities
            .filter { it.type == EntityType.NATURAL }
            .map { it.name }
            .toSet()

        if (naturalEntityNames.size > 1) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "Semantic models can have at most one natural entity, but semantic model " +
                        "`${semanticModel.name}` has ${naturalEntityNames.size} distinct natural entities set! " +
                        "$naturalEntityNames.",
                ),
            )
        }
        if (naturalEntityNames.isNotEmpty() &&
            semanticModel.dimensions.none { it.validityParams != null }
        ) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "The use of `natural` entities is currently supported only in conjunction with a validity " +
                        "window defined in the set of time dimensions associated with the semantic model. Semantic model " +
                        "`${semanticModel.name}` uses a natural entity ($naturalEntityNames) but does not define a " +
                        "validity window!",
                ),
            )
        }
        return issues
    }
}
