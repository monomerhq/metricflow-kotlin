package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest

/**
 * One validation check.
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py::SemanticManifestValidationRule`.
 *
 * Each implementation reads an already-transformed [SemanticManifest] and returns zero or more
 * [ValidationIssue]s. Rules are pure functions of the input — no side effects, no I/O. The
 * [SemanticManifestValidator] catches any throwables and converts them into a
 * [ValidationError] (see `validate_safely`-style wrapping in [SemanticManifestValidator.runRule]).
 *
 * Implementations should be singleton `object`s so the [DefaultValidationRules] list can hold
 * references without per-instance allocation.
 */
fun interface SemanticManifestValidationRule {
    /** Apply this rule to [semanticManifest] and return any issues found. */
    fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue>
}
