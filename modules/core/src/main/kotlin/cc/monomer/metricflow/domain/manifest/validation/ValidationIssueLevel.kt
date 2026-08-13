package cc.monomer.metricflow.domain.manifest.validation

/**
 * Severity of a [ValidationIssue].
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py::ValidationIssueLevel`.
 *
 * - [WARNING] — informational; doesn't prevent the model from being used.
 * - [FUTURE_ERROR] — currently a warning but will become an error in a later metricflow release.
 * - [ERROR] — prevents the model from being used. Python's `checked_validations` raises on these.
 *
 * The `value` integer mirrors Python's enum value (used for ordering: WARNING < FUTURE_ERROR < ERROR).
 * The serialized JSON form is the enum *name* (`"ERROR"`, `"WARNING"`, `"FUTURE_ERROR"`) — that matches
 * what Python's `issue.level.name` emits and what the corpus oracle expectations contain.
 */
enum class ValidationIssueLevel(val value: Int) {
    WARNING(0),
    FUTURE_ERROR(1),
    ERROR(2);

    /** Plural form mirroring Python's `name_plural` (`"WARNING"` -> `"WARNINGS"`). */
    val namePlural: String get() = "${name}S"
}
