package cc.monomer.metricflow.domain.manifest.validation

/**
 * Aggregate output of a [SemanticManifestValidator] run.
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py::SemanticManifestValidationResults`.
 *
 * Issues are partitioned into the three severity buckets; [allIssues] joins them in Python's
 * order (errors first, then future errors, then warnings).
 *
 * `hasBlockingIssues` matches Python — true iff [errors] is non-empty. Callers (engine init,
 * `validateManifest` RPC, CLI) inspect this rather than catching exceptions: the Kotlin
 * validator NEVER throws on a blocking issue, mirroring Python's `validate_semantic_manifest`
 * entry point (the throwing variant is `checked_validations`, which we deliberately do not
 * expose — see PROGRESS.md Phase 1a insight).
 */
data class SemanticManifestValidationResults(
    val warnings: List<ValidationWarning> = emptyList(),
    val futureErrors: List<ValidationFutureError> = emptyList(),
    val errors: List<ValidationError> = emptyList(),
) {
    /** True iff any [ValidationError]s were collected. */
    val hasBlockingIssues: Boolean get() = errors.isNotEmpty()

    /** All issues, with errors first, then future errors, then warnings. Mirrors Python's `all_issues`. */
    val allIssues: List<ValidationIssue>
        get() = errors + futureErrors + warnings

    companion object {
        /** Bucket the given [issues] by severity. Mirrors Python's `from_issues_sequence`. */
        fun fromIssues(issues: Sequence<ValidationIssue>): SemanticManifestValidationResults {
            val warnings = mutableListOf<ValidationWarning>()
            val futureErrors = mutableListOf<ValidationFutureError>()
            val errors = mutableListOf<ValidationError>()
            for (issue in issues) {
                when (issue) {
                    is ValidationError -> errors.add(issue)
                    is ValidationFutureError -> futureErrors.add(issue)
                    is ValidationWarning -> warnings.add(issue)
                }
            }
            return SemanticManifestValidationResults(
                warnings = warnings,
                futureErrors = futureErrors,
                errors = errors,
            )
        }

        /** Concat several [SemanticManifestValidationResults] into one. Mirrors Python's `merge`. */
        fun merge(results: List<SemanticManifestValidationResults>): SemanticManifestValidationResults {
            val warnings = mutableListOf<ValidationWarning>()
            val futureErrors = mutableListOf<ValidationFutureError>()
            val errors = mutableListOf<ValidationError>()
            for (r in results) {
                warnings.addAll(r.warnings)
                futureErrors.addAll(r.futureErrors)
                errors.addAll(r.errors)
            }
            return SemanticManifestValidationResults(
                warnings = warnings,
                futureErrors = futureErrors,
                errors = errors,
            )
        }
    }
}
