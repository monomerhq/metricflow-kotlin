package cc.monomer.metricflow.domain.spec.bind

import kotlinx.serialization.Serializable

/**
 * The supported SQL join keywords. Values are the literal SQL fragment emitted by the renderers.
 *
 * Port of `metricflow_semantics.sql.sql_join_type.SqlJoinType`.
 */
@Serializable
enum class SqlJoinType(val sql: String) {
    LEFT_OUTER("LEFT OUTER JOIN"),
    FULL_OUTER("FULL OUTER JOIN"),
    INNER("INNER JOIN"),
    CROSS_JOIN("CROSS JOIN");

    override fun toString(): String = "SqlJoinType.$name"
}
