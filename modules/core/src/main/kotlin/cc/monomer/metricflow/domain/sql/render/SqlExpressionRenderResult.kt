package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * The result of rendering one [cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode]
 * to a SQL fragment plus the bind parameters needed to execute it.
 *
 * Port of `metricflow.sql.render.expr_renderer.SqlExpressionRenderResult`.
 */
data class SqlExpressionRenderResult(
    val sql: String,
    val bindParameterSet: SqlBindParameterSet,
)
