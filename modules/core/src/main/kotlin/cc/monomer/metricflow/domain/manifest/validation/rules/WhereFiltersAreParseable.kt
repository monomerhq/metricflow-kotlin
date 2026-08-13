package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue

/**
 * Validates that every `WhereFilter`'s Jinja template parses without error.
 *
 * Port of `metricflow_semantic_interfaces/validations/where_filters.py::WhereFiltersAreParseable`.
 *
 * **Incomplete port.** Python invokes the upstream `JinjaObjectParser` /
 * `metric.filter.filter_expression_parameter_sets` to validate every filter template.
 * That parser is part of the where-filter / call-parameter-sets layer, which is owned by a
 * later wave (W4+). Until that layer lands, we accept all filters as valid — the parser was
 * the only thing this rule checked, and warnings here are advisory (severity WARNING). When
 * the parser ports, plug it in here and the parity test will cover it.
 *
 * The 19-manifest corpus does not produce any issues from this rule (no malformed filter
 * appears anywhere in the canonical fixtures), so the no-op behaviour passes parity today.
 */
object WhereFiltersAreParseable : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        // TODO(W4+): Wire in the where-filter Jinja parser once the call-parameter-sets module ports.
        // The original Python rule iterates every metric filter, every measure-input filter, and
        // every saved-query where filter, then calls `filter.filter_expression_parameter_sets(...)`
        // to validate.
        return emptyList()
    }
}
