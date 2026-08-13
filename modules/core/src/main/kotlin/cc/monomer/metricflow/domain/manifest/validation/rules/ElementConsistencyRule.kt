package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that elements (measures / dimensions / entities) with the same name across semantic
 * models share the same element type — e.g. a name used as both an `entity` and a `dimension`
 * is rejected.
 *
 * Port of `metricflow_semantic_interfaces/validations/element_const.py::ElementConsistencyRule`.
 */
object ElementConsistencyRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val elementNameToTypes = collectElementNameToTypes(semanticManifest)
        val invalidElements = elementNameToTypes.filter { (_, typeMap) -> typeMap.size > 1 }

        for ((elementName, typeToContexts) in invalidElements) {
            // Sort by enum value to match Python's `SemanticModelElementType(v) for v in sorted(...)`.
            val typesUsed = typeToContexts.keys.sortedBy { it.value }
            for (elementType in typesUsed) {
                val contexts = typeToContexts[elementType] ?: continue
                val semanticModelNames = contexts.map { it.semanticModel.semanticModelName }.toSet()
                val typesUsedStr = "[" + typesUsed.joinToString(", ") { it.toString() } + "]"
                issues.add(
                    ValidationError(
                        context = contexts.first(),
                        message = "In semantic models $semanticModelNames, element `$elementName` is of type " +
                            "$elementType, but it is used as types $typesUsedStr across the model.",
                    ),
                )
            }
        }
        return issues
    }

    private fun collectElementNameToTypes(
        semanticManifest: SemanticManifest,
    ): Map<String, Map<SemanticModelElementType, List<SemanticModelContext>>> {
        val elementTypes = mutableMapOf<String, MutableMap<SemanticModelElementType, MutableList<SemanticModelContext>>>()
        for (semanticModel in semanticManifest.semanticModels) {
            val ctx = SemanticModelContext(
                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                semanticModel = SemanticModelReference(semanticModelName = semanticModel.name),
            )
            for (measure in semanticModel.measures) {
                elementTypes.getOrPut(measure.name) { mutableMapOf() }
                    .getOrPut(SemanticModelElementType.MEASURE) { mutableListOf() }
                    .add(ctx)
            }
            for (dimension in semanticModel.dimensions) {
                elementTypes.getOrPut(dimension.name) { mutableMapOf() }
                    .getOrPut(SemanticModelElementType.DIMENSION) { mutableListOf() }
                    .add(ctx)
            }
            for (entity in semanticModel.entities) {
                elementTypes.getOrPut(entity.name) { mutableMapOf() }
                    .getOrPut(SemanticModelElementType.ENTITY) { mutableListOf() }
                    .add(ctx)
            }
        }
        return elementTypes
    }
}
