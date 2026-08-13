package cc.monomer.metricflow.domain.spec.bind

import kotlinx.serialization.Serializable

/**
 * A single named SQL bind parameter — i.e. a `(key, value)` pair that will be substituted
 * into a rendered SQL string at execution time.
 *
 * Port of `metricflow_semantics.sql.sql_bind_parameters.SqlBindParameter`.
 */
@Serializable
data class SqlBindParameter(val key: String, val value: SqlBindParameterValue)
