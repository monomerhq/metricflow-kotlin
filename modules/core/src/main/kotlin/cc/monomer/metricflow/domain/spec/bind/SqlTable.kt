package cc.monomer.metricflow.domain.spec.bind

import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import kotlinx.serialization.Serializable

/**
 * The kind of relation a [SqlTable] points at.
 *
 * Port of `metricflow_semantics.sql.sql_table.SqlTableType`. The Python comment notes
 * "CTE type may be added later" — preserved verbatim.
 */
@Serializable
enum class SqlTableType {
    TABLE,
    VIEW,
    // CTE type may be added later.
}

/**
 * A canonical reference to a SQL table — the `(database, schema, table)` triple.
 *
 * Port of `metricflow_semantics.sql.sql_table.SqlTable`. Compared to [NodeRelation], this
 * type is the one consumed by SQL plan nodes and dialect renderers — it has no concept of
 * a free-form "alias" and serialises directly to a dotted SQL fragment via [sql].
 *
 * Invariant (enforced in `init`): if [dbName] is non-null then [schemaName] must also be
 * non-null. A `<db>.<table>` form without an intervening schema is rejected by both
 * Python and Kotlin.
 */
@Serializable
data class SqlTable(
    val schemaName: String?,
    val tableName: String,
    val dbName: String? = null,
    val tableType: SqlTableType = SqlTableType.TABLE,
) : Comparable<SqlTable> {

    init {
        require(!(dbName != null && schemaName == null)) {
            "dbName=$dbName when it should be specified with schemaName=$schemaName"
        }
    }

    /**
     * The dotted SQL fragment for this table — e.g. `mydb.myschema.mytable`,
     * or `myschema.mytable` if [dbName] is null.
     *
     * Port of `SqlTable.sql`.
     */
    val sql: String
        get() = buildString {
            if (dbName != null) {
                append(dbName)
                append('.')
            }
            if (schemaName != null) {
                append(schemaName)
                append('.')
            }
            append(tableName)
        }

    override fun compareTo(other: SqlTable): Int =
        compareValuesBy(
            this,
            other,
            { it.schemaName ?: "" },
            { it.tableName },
            { it.dbName ?: "" },
            { it.tableType },
        )

    companion object {
        /**
         * Parse a dotted SQL fragment into a [SqlTable].
         *
         * Port of `SqlTable.from_string`. Accepts `<schema>.<table>` (2 parts) or
         * `<db>.<schema>.<table>` (3 parts); anything else throws.
         */
        fun fromString(sqlStr: String): SqlTable {
            val parts = sqlStr.split(".")
            return when (parts.size) {
                2 -> SqlTable(schemaName = parts[0], tableName = parts[1])
                3 -> SqlTable(
                    dbName = parts[0],
                    schemaName = parts[1],
                    tableName = parts[2],
                )
                else -> throw IllegalArgumentException(
                    "Invalid input for a SQL table, expected form '<schema>.<table>' or " +
                        "'<db>.<schema>.<table>' but got: $sqlStr",
                )
            }
        }

        /** Convenience to build a [SqlTable] from a manifest [NodeRelation]. */
        fun fromNodeRelation(nodeRelation: NodeRelation): SqlTable =
            fromString(nodeRelation.relationName)
    }
}
