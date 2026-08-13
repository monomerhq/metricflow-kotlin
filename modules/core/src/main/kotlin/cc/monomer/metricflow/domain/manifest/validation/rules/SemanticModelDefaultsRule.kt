package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelValidationHelpers
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that the `defaults.agg_time_dimension` of every semantic model points at a real
 * TIME dimension in the same model.
 *
 * Port of `metricflow_semantic_interfaces/validations/semantic_models.py::SemanticModelDefaultsRule`.
 */
object SemanticModelDefaultsRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(validate(semanticModel))
        }
        return issues
    }

    private fun validate(semanticModel: SemanticModel): List<ValidationIssue> {
        val agg = semanticModel.defaults?.aggTimeDimension ?: return emptyList()
        if (SemanticModelValidationHelpers.timeDimensionInModel(agg, semanticModel)) return emptyList()
        return listOf(
            ValidationError(
                context = SemanticModelContext(
                    fileContext = FileContext.fromMetadata(semanticModel.metadata),
                    semanticModel = SemanticModelReference(semanticModelName = semanticModel.name),
                ),
                message = "Default aggregation time dimension was specified as '$agg' which " +
                    "doesn't exist as a time dimension in semantic model named '${semanticModel.name}'.",
            ),
        )
    }
}
