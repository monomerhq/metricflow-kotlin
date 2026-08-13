package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Runs every configured [SemanticManifestValidationRule] against a [SemanticManifest] and
 * returns the aggregated [SemanticManifestValidationResults].
 *
 * Port of `metricflow_semantic_interfaces/validations/semantic_manifest_validator.py::SemanticManifestValidator`.
 *
 * **Never throws on a blocking issue.** The result object's [SemanticManifestValidationResults.hasBlockingIssues]
 * tells the caller whether errors were present. (Python exposes both `validate_semantic_manifest`
 * — non-throwing — and `checked_validations` — throwing. We only port the non-throwing one;
 * the gRPC `validateManifest` contract and the engine init path both want issues back as data.
 * See PROGRESS.md Phase 1a insight.)
 *
 * Each rule is invoked through [runRule], which mirrors Python's `validate_safely` decorator:
 * any [Throwable] thrown by the rule is wrapped into a [ValidationError] tagged with the rule's
 * class name and the stack trace, so a buggy rule cannot break the rest of validation.
 *
 * Construction style: this is a class with two overloaded [validate] methods (no default value
 * parameter — backend-conventions "Explicit Code"). Use [DefaultValidationRules] for the canonical
 * 28-rule pipeline.
 */
class SemanticManifestValidator(
    private val rules: List<SemanticManifestValidationRule>,
) {
    init {
        require(rules.isNotEmpty()) {
            "SemanticManifestValidator 'rules' must be a non-empty list of SemanticManifestValidationRule."
        }
    }

    /** Apply [rules] to [semanticManifest] and return the aggregated issues. */
    fun validate(semanticManifest: SemanticManifest): SemanticManifestValidationResults {
        val perRule = rules.map { rule ->
            SemanticManifestValidationResults.fromIssues(
                runRule(rule, semanticManifest).asSequence(),
            )
        }
        return SemanticManifestValidationResults.merge(perRule)
    }

    /**
     * Invoke [rule] and convert any thrown [Throwable] into a single [ValidationError] issue.
     *
     * Mirrors Python's `validate_safely` decorator semantics: a rule crash should not propagate;
     * it should surface as an issue so the rest of validation still runs.
     */
    private fun runRule(
        rule: SemanticManifestValidationRule,
        semanticManifest: SemanticManifest,
    ): List<ValidationIssue> {
        return try {
            rule.validateManifest(semanticManifest)
        } catch (t: Throwable) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            listOf(
                ValidationError(
                    message = "An error occurred while running validation rule " +
                        "`${rule::class.simpleName}` - ${t::class.simpleName}: ${t.message ?: ""}",
                    extraDetail = "stacktrace: ${sw.toString().trim()}",
                ),
            )
        }
    }

    companion object {
        /**
         * Factory: a validator configured with [DefaultValidationRules] — the canonical
         * 28-rule pipeline. Mirrors Python's no-argument constructor.
         */
        fun withDefaultRules(): SemanticManifestValidator =
            SemanticManifestValidator(DefaultValidationRules)
    }
}
