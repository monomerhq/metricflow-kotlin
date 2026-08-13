package cc.monomer.metricflow.domain.manifest.model

import kotlinx.serialization.Serializable

/**
 * Path to the data — the (database, schema, alias) triple that locates a semantic model's
 * underlying physical relation.
 *
 * Port of `metricflow_semantic_interfaces/implementations/node_relation.py::PydanticNodeRelation`.
 *
 * `relationName` is the dot-joined "fully qualified" name; in Python the constructor's
 * validator computes it lazily if absent. Here we leave it as a field — corpus JSON
 * either supplies it or omits it (default empty string preserves round-trip when both
 * forms appear).
 */
@Serializable
data class NodeRelation(
    val alias: String,
    val schemaName: String,
    val database: String? = null,
    val relationName: String = "",
) {
    companion object {
        /**
         * Parse a dotted SQL relation name.
         *
         * `<schema>.<table>` -> `(schemaName, alias)`.
         * `<database>.<schema>.<table>` -> `(database, schemaName, alias)`.
         */
        fun fromString(sqlStr: String): NodeRelation {
            val parts = sqlStr.split(".")
            return when (parts.size) {
                2 -> NodeRelation(schemaName = parts[0], alias = parts[1])
                3 -> NodeRelation(database = parts[0], schemaName = parts[1], alias = parts[2])
                else -> throw IllegalArgumentException(
                    "Invalid input for a SQL table, expected form '<schema>.<table>' or " +
                        "'<db>.<schema>.<table>' but got: $sqlStr",
                )
            }
        }
    }
}
