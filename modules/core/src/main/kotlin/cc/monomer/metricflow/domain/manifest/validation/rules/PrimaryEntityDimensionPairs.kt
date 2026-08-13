package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Asserts that every `(primary_entity, dimension)` pair is unique across the manifest. Two
 * semantic models with the same primary entity may not share a dimension name.
 *
 * Port of `metricflow_semantic_interfaces/validations/unique_valid_name.py::PrimaryEntityDimensionPairs`.
 */
object PrimaryEntityDimensionPairs : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val knownPairings = mutableMapOf<String, MutableMap<String, String>>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(checkSemanticModel(semanticModel, knownPairings))
        }
        return issues
    }

    private fun checkSemanticModel(
        semanticModel: SemanticModel,
        knownPairings: MutableMap<String, MutableMap<String, String>>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        var primaryEntity: String? = semanticModel.primaryEntity
        if (primaryEntity == null) {
            for (entity in semanticModel.entities) {
                if (entity.type == EntityType.PRIMARY) {
                    primaryEntity = entity.name
                    break
                }
            }
        }
        if (primaryEntity == null) return issues

        val isNew = primaryEntity !in knownPairings
        val pairings = knownPairings.getOrPut(primaryEntity) { mutableMapOf() }

        for (dimension in semanticModel.dimensions) {
            if (isNew || dimension.name !in pairings) {
                pairings[dimension.name] = semanticModel.name
            } else {
                issues.add(
                    ValidationError(
                        context = SemanticModelElementContext(
                            fileContext = FileContext.fromMetadata(semanticModel.metadata),
                            semanticModelElement = SemanticModelElementReference(
                                semanticModelName = semanticModel.name,
                                elementName = dimension.name,
                            ),
                            elementType = SemanticModelElementType.DIMENSION,
                        ),
                        message = "Duplicate dimension + primary entity pairing detected, dimension + primary entity " +
                            "pairings must be unique. Semantic model `${semanticModel.name}` has a primary entity of " +
                            "`$primaryEntity` and dimension `${dimension.name}`, but this pairing is already in use on " +
                            "semantic model `${pairings[dimension.name]}`.",
                    ),
                )
            }
        }
        return issues
    }
}
