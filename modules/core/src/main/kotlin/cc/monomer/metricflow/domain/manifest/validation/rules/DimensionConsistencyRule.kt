package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.DimensionInvariants
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks for consistent dimension properties across semantic models:
 *
 * * Dimensions sharing a name should have the same type.
 * * Dimensions sharing a name should be either all partitions or none.
 *
 * Port of `metricflow_semantic_interfaces/validations/dimension_const.py::DimensionConsistencyRule`.
 */
object DimensionConsistencyRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val dimensionToInvariant = mutableMapOf<DimensionReference, DimensionInvariants>()
        val issues = mutableListOf<ValidationIssue>()

        for (semanticModel in semanticManifest.semanticModels) {
            for (dimension in semanticModel.dimensions) {
                val invariant = dimensionToInvariant[dimension.reference]
                if (invariant == null) {
                    dimensionToInvariant[dimension.reference] = DimensionInvariants(
                        type = dimension.type,
                        isPartition = dimension.isPartition,
                    )
                    continue
                }
                val isPartition = dimension.isPartition

                val ctx = SemanticModelElementContext(
                    fileContext = FileContext.fromMetadata(semanticModel.metadata),
                    semanticModelElement = SemanticModelElementReference(
                        semanticModelName = semanticModel.name,
                        elementName = dimension.name,
                    ),
                    elementType = SemanticModelElementType.DIMENSION,
                )

                if (invariant.type != dimension.type) {
                    issues.add(
                        ValidationError(
                            context = ctx,
                            message = "In semantic model `${semanticModel.name}`, type conflict for dimension " +
                                "`${dimension.name}` - already in model as type `${invariant.type}` but got " +
                                "`${dimension.type}`",
                        ),
                    )
                }
                if (invariant.isPartition != isPartition) {
                    issues.add(
                        ValidationError(
                            context = ctx,
                            message = "In semantic model `${semanticModel.name}, conflicting is_partition attribute for " +
                                "dimension `${dimension.reference}` - already in model" +
                                " with is_partition as `${invariant.isPartition}` but got " +
                                "`$isPartition``",
                        ),
                    )
                }
            }
        }
        return issues
    }
}
