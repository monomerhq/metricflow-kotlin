package cc.monomer.metricflow.domain.manifest.validation

/**
 * Categorises the kind of element a [ValidationIssue] is talking about.
 *
 * Port of `metricflow_semantic_interfaces/validations/validator_helpers.py::SemanticModelElementType`.
 *
 * The `value` lowercase form mirrors Python's enum value; it is what gets embedded into
 * `context_str()` strings (e.g. `"with measure ..."`, `"with dimension ..."`).
 */
enum class SemanticModelElementType(val value: String) {
    MEASURE("measure"),
    DIMENSION("dimension"),
    ENTITY("entity");

    override fun toString(): String = "SemanticModelElementType.$name"
}

/**
 * Categorises which field of a saved-query a [ValidationIssue] is about.
 *
 * Port of `validator_helpers.py::SavedQueryElementType`.
 */
enum class SavedQueryElementType(val value: String) {
    METRIC("metric"),
    GROUP_BY("group by"),
    WHERE("where"),
    ORDER_BY("order by"),
    LIMIT("limit");

    override fun toString(): String = "SavedQueryElementType.$name"
}
