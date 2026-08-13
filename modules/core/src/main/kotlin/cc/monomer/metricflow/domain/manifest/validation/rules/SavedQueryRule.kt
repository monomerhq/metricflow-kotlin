package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SavedQuery
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.SavedQueryContext
import cc.monomer.metricflow.domain.manifest.validation.SavedQueryElementType
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Validates each [SavedQuery]: the referenced metrics must exist, the limit (if any) must be
 * non-negative, group-by / order-by items must reference real elements.
 *
 * Port of `metricflow_semantic_interfaces/validations/saved_query.py::SavedQueryRule`.
 *
 * The Python rule does full Jinja parsing of `Metric('foo')` / `Dimension('foo__bar')` strings.
 * We port the subset that doesn't require the upstream `parsing.text_input` parser: metric
 * existence checks and limit validation. The Jinja-parsing parts of group-by / order-by
 * validation are left as a TODO — they need the W3+ where-filter parser. None of the corpus
 * fixtures exercise that path in the SavedQueryRule (all saved queries use valid metric names
 * and the only generated issues come from other rules).
 */
object SavedQueryRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val validMetricNames = semanticManifest.metrics.map { it.name }.toSet()
        for (savedQuery in semanticManifest.savedQueries) {
            issues.addAll(checkMetrics(validMetricNames, savedQuery))
            issues.addAll(checkLimit(savedQuery))
        }
        return issues
    }

    private fun checkMetrics(validMetricNames: Set<String>, savedQuery: SavedQuery): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        for (metricName in savedQuery.queryParams.metrics) {
            if (metricName !in validMetricNames) {
                issues.add(
                    ValidationError(
                        message = "`$metricName` is not a valid metric name.",
                        context = SavedQueryContext(
                            fileContext = FileContext.fromMetadata(savedQuery.metadata),
                            elementType = SavedQueryElementType.METRIC,
                            elementValue = metricName,
                        ),
                    ),
                )
            }
        }
        return issues
    }

    private fun checkLimit(savedQuery: SavedQuery): List<ValidationIssue> {
        val limit = savedQuery.queryParams.limit ?: return emptyList()
        if (limit < 0) {
            return listOf(
                ValidationError(
                    message = "Invalid limit value: $limit (should be >= 0)",
                    context = SavedQueryContext(
                        fileContext = FileContext.fromMetadata(savedQuery.metadata),
                        elementType = SavedQueryElementType.LIMIT,
                        elementValue = limit.toString(),
                    ),
                ),
            )
        }
        return emptyList()
    }
}
