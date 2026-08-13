package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelValidationHelpers
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that the `agg_time_dimension` of a measure points to a real TIME dimension in the
 * same semantic model.
 *
 * Port of `metricflow_semantic_interfaces/validations/agg_time_dimension.py::AggregationTimeDimensionRule`.
 */
object AggregationTimeDimensionRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            issues.addAll(validateSemanticModel(semanticModel))
        }
        return issues
    }

    private fun validateSemanticModel(semanticModel: SemanticModel): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (measure in semanticModel.measures) {
            val ctx = SemanticModelElementContext(
                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                semanticModelElement = SemanticModelElementReference(
                    semanticModelName = semanticModel.name,
                    elementName = measure.name,
                ),
                elementType = SemanticModelElementType.MEASURE,
            )
            val aggTimeDimRef = try {
                semanticModel.checkedAggTimeDimensionForMeasure(measure.reference)
            } catch (_: IllegalStateException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (!SemanticModelValidationHelpers.timeDimensionInModel(aggTimeDimRef.elementName, semanticModel)) {
                issues.add(
                    ValidationError(
                        context = ctx,
                        message = "In semantic model '${semanticModel.name}', measure '${measure.name}' has the " +
                            "aggregation time dimension set to '${aggTimeDimRef.elementName}', " +
                            "which is not a valid time dimension in the semantic model",
                    ),
                )
            }
        }
        return issues
    }
}
