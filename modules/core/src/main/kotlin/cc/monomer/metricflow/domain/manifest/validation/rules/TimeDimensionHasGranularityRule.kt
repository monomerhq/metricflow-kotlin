package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationFutureError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import java.time.LocalDate

/**
 * Warns about TIME dimensions that don't declare a granularity. Currently a [ValidationFutureError]
 * with cutover date 2027-01-01.
 *
 * Port of `metricflow_semantic_interfaces/validations/time_dimension_has_granularity.py::TimeDimensionHasGranularityRule`.
 */
object TimeDimensionHasGranularityRule : SemanticManifestValidationRule {
    private val ERROR_DATE: LocalDate = LocalDate.of(2027, 1, 1)

    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            for (dimension in semanticModel.dimensions) {
                if (dimension.type != DimensionType.TIME) continue
                val granularity = dimension.typeParams?.timeGranularity
                if (granularity == null) {
                    issues.add(
                        ValidationFutureError(
                            context = SemanticModelElementContext(
                                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                                semanticModelElement = SemanticModelElementReference(
                                    semanticModelName = semanticModel.name,
                                    elementName = dimension.name,
                                ),
                                elementType = SemanticModelElementType.DIMENSION,
                            ),
                            message = "In semantic model `${semanticModel.name}`, time dimension `${dimension.name}` " +
                                "must have a time granularity set.",
                            errorDate = ERROR_DATE,
                        ),
                    )
                }
            }
        }
        return issues
    }
}
