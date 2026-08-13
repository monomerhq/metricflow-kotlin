package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * The result of rendering an [cc.monomer.metricflow.domain.sql.plan.SqlPlanNode] —
 * the SQL string that could be run plus the bind parameters that should accompany it.
 *
 * Port of `metricflow.sql.render.sql_plan_renderer.SqlPlanRenderResult`.
 */
data class SqlPlanRenderResult(
    val sql: String,
    val bindParameterSet: SqlBindParameterSet,
)
