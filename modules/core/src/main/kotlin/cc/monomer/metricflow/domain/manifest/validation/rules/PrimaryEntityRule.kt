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
 * Checks that the primary entity for every semantic model with dimensions is set either by
 * `primary_entity` or by exactly one entity of type [EntityType.PRIMARY].
 *
 * Port of `metricflow_semantic_interfaces/validations/primary_entity.py::PrimaryEntityRule`.
 */
object PrimaryEntityRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(checkModel(semanticModel))
        }
        return issues
    }

    private fun checkModel(semanticModel: SemanticModel): List<ValidationIssue> {
        val ctx = SemanticModelContext(
            fileContext = FileContext.fromMetadata(semanticModel.metadata),
            semanticModel = SemanticModelReference(semanticModelName = semanticModel.name),
        )
        val entitiesWithPrimaryType = semanticModel.entities.filter { it.type == EntityType.PRIMARY }
        if (entitiesWithPrimaryType.isNotEmpty()) {
            if (entitiesWithPrimaryType.size > 1) {
                val names = entitiesWithPrimaryType.map { it.name }
                return listOf(
                    ValidationError(
                        context = ctx,
                        message = "Semantic models can have only one primary entity. The semantic model" +
                            " `${semanticModel.name}` has ${names.size}: " +
                            names.joinToString(", "),
                    ),
                )
            }
            val entity = entitiesWithPrimaryType[0]
            if (semanticModel.primaryEntityReference != null) {
                return listOf(
                    ValidationError(
                        context = ctx,
                        message = "The semantic model `${semanticModel.name}` has an entity named " +
                            "`${entity.name}` with type primary but it also has the `primary_entity` " +
                            "field set to `${semanticModel.primaryEntityReference!!.elementName}`. Both should not " +
                            "be present in the model.",
                    ),
                )
            }
        }
        if (semanticModel.dimensions.isNotEmpty() &&
            semanticModel.primaryEntityReference == null &&
            entitiesWithPrimaryType.isEmpty()
        ) {
            return listOf(
                ValidationError(
                    context = ctx,
                    message = "The semantic model ${semanticModel.name} contains dimensions, but it does not define a " +
                        "primary entity. Either add an entity with type PRIMARY or set a value for the " +
                        "primary_entity key.",
                ),
            )
        }
        return emptyList()
    }
}
