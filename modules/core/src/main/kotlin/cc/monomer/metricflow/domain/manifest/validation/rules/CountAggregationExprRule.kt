package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.SharedMeasureAndMetricHelpers
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that COUNT-aggregating measures supply an `expr`.
 *
 * Port of `metricflow_semantic_interfaces/validations/measures.py::CountAggregationExprRule`.
 */
object CountAggregationExprRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            for (measure in semanticModel.measures) {
                val ctx = SemanticModelElementContext(
                    fileContext = FileContext.fromMetadata(semanticModel.metadata),
                    semanticModelElement = SemanticModelElementReference(
                        semanticModelName = semanticModel.name,
                        elementName = measure.name,
                    ),
                    elementType = SemanticModelElementType.MEASURE,
                )
                issues.addAll(
                    SharedMeasureAndMetricHelpers.validateExprForCountAggregation(
                        context = ctx,
                        objectName = measure.name,
                        objectType = "Measure",
                        aggType = measure.agg,
                        expr = measure.expr,
                    ),
                )
            }
        }
        return issues
    }
}
