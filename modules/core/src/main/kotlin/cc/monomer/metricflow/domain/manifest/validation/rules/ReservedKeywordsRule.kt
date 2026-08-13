package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticModelElementType
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Checks that names selected by name (rather than by SQL `expr`) aren't reserved SQL keywords
 * shared by all supported engines.
 *
 * Port of `metricflow_semantic_interfaces/validations/reserved_keywords.py::ReservedKeywordsRule`.
 */
object ReservedKeywordsRule : SemanticManifestValidationRule {
    /**
     * Non-exhaustive list — only the intersection of keywords reserved across redshift,
     * postgres, bigquery and snowflake. Engine-specific keywords (e.g. `USER` in Redshift)
     * are caught by data-warehouse validation, not by semantic validation.
     */
    private val RESERVED_KEYWORDS: Set<String> = setOf(
        "AND", "AS", "CREATE", "DISTINCT", "FOR", "FROM", "FULL", "HAVING", "IN", "INNER",
        "INTO", "IS", "JOIN", "LEFT", "LIKE", "NATURAL", "NOT", "NULL", "ON", "OR",
        "RIGHT", "SELECT", "UNION", "USING", "WHERE", "WITH",
    )

    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (semanticModel in semanticManifest.semanticModels) {
            // Node relation parts
            val relationName = semanticModel.nodeRelation.relationName
            val parts = if (relationName.isNotEmpty()) {
                relationName.split(".").map { it.uppercase() }.toSet()
            } else {
                emptySet()
            }
            val keywordIntersection = RESERVED_KEYWORDS.intersect(parts)
            if (keywordIntersection.isNotEmpty()) {
                issues.add(
                    ValidationError(
                        context = SemanticModelContext(
                            fileContext = FileContext.fromMetadata(semanticModel.metadata),
                            semanticModel = semanticModel.reference,
                        ),
                        message = "'$relationName' contains the SQL reserved keyword(s) " +
                            "$keywordIntersection, and thus cannot be used for 'node_relation'.",
                    ),
                )
            }
            issues.addAll(validateSubElements(semanticModel))
        }
        return issues
    }

    private fun validateSubElements(semanticModel: SemanticModel): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (dimension in semanticModel.dimensions) {
            if (dimension.name.uppercase() in RESERVED_KEYWORDS) {
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
                        message = "'${dimension.name}' is an SQL reserved keyword, and thus cannot be used as a " +
                            "dimension 'name'.",
                    ),
                )
            }
        }
        for (entity in semanticModel.entities) {
            if (entity.name.uppercase() in RESERVED_KEYWORDS) {
                issues.add(
                    ValidationError(
                        context = SemanticModelElementContext(
                            fileContext = FileContext.fromMetadata(semanticModel.metadata),
                            semanticModelElement = SemanticModelElementReference(
                                semanticModelName = semanticModel.name,
                                elementName = entity.name,
                            ),
                            elementType = SemanticModelElementType.ENTITY,
                        ),
                        message = "'${entity.name}' is an SQL reserved keyword, and thus cannot be used as an entity 'name'",
                    ),
                )
            }
        }
        for (measure in semanticModel.measures) {
            if (measure.name.uppercase() in RESERVED_KEYWORDS) {
                issues.add(
                    ValidationError(
                        context = SemanticModelElementContext(
                            fileContext = FileContext.fromMetadata(semanticModel.metadata),
                            semanticModelElement = SemanticModelElementReference(
                                semanticModelName = semanticModel.name,
                                elementName = measure.name,
                            ),
                            elementType = SemanticModelElementType.MEASURE,
                        ),
                        message = "'${measure.name}' is an SQL reserved keyword, and thus cannot be used as a " +
                            "measure 'name'.",
                    ),
                )
            }
        }
        return issues
    }
}
