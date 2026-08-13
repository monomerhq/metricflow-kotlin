package cc.monomer.metricflow.domain.manifest.validation

import java.time.LocalDate

/**
 * One finding emitted by a [SemanticManifestValidationRule]. Carries the severity, a human-readable
 * message, optional [ValidationContext] for where in the manifest the issue was found, and
 * optional extra detail.
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py::ValidationIssue`
 * (abstract base) + its three concrete subclasses [ValidationError], [ValidationWarning],
 * [ValidationFutureError].
 *
 * The Kotlin shape mirrors Python: each subclass exposes a fixed [level], and [ValidationFutureError]
 * additionally carries an [errorDate]. We use a `sealed class` so consumers can `when (issue) { ... }`
 * exhaustively. The class is open enough for kotlinx-serialization to handle each variant via
 * dedicated serializer — we don't auto-derive because the parity test compares against the
 * Python `oracle.serialize.issue_to_dict` shape, which has additional derived fields
 * (`context_str`, `readable`); those are produced by [SemanticManifestValidationResultsSerializer].
 */
sealed class ValidationIssue {
    abstract val level: ValidationIssueLevel
    abstract val message: String
    abstract val context: ValidationContext?
    abstract val extraDetail: String?

    /**
     * Return an easily-readable string, mirroring Python's `as_readable_str(verbose=False)`.
     *
     * Format: `"<LEVEL>: <context_str> - <message>"`; the `<context_str> - ` chunk is omitted when
     * the context string is empty. [ValidationFutureError] appends a date warning.
     */
    open fun asReadableStr(): String {
        val prefix = level.name
        val ctxStr = context?.contextStr().orEmpty()
        val joiner = if (ctxStr.isNotEmpty()) " - " else ""
        return "$prefix: $ctxStr$joiner$message"
    }
}

/**
 * A blocking issue. The manifest must be fixed before metricflow can run on it.
 *
 * Port of `validator_helpers.py::ValidationError`.
 */
data class ValidationError(
    override val message: String,
    override val context: ValidationContext? = null,
    override val extraDetail: String? = null,
) : ValidationIssue() {
    override val level: ValidationIssueLevel = ValidationIssueLevel.ERROR
}

/**
 * A non-blocking issue. The model still works, but the configuration is probably wrong.
 *
 * Port of `validator_helpers.py::ValidationWarning`.
 */
data class ValidationWarning(
    override val message: String,
    override val context: ValidationContext? = null,
    override val extraDetail: String? = null,
) : ValidationIssue() {
    override val level: ValidationIssueLevel = ValidationIssueLevel.WARNING
}

/**
 * A currently-warning issue that will become an error after [errorDate].
 *
 * Port of `validator_helpers.py::ValidationFutureError`.
 */
data class ValidationFutureError(
    override val message: String,
    val errorDate: LocalDate,
    override val context: ValidationContext? = null,
    override val extraDetail: String? = null,
) : ValidationIssue() {
    override val level: ValidationIssueLevel = ValidationIssueLevel.FUTURE_ERROR

    override fun asReadableStr(): String {
        val base = super.asReadableStr()
        val formatted = errorDate.format(MONTH_DAY_YEAR)
        return "${base}IMPORTANT: this error will break your model starting $formatted. "
    }

    private companion object {
        /** Mirrors Python's `error_date.strftime('%b %d, %Y')` — e.g. `"Jan 01, 2027"`. */
        val MONTH_DAY_YEAR: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("MMM dd, yyyy", java.util.Locale.US)
    }
}
