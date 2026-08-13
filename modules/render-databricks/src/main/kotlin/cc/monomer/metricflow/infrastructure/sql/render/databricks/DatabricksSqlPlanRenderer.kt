package cc.monomer.metricflow.infrastructure.sql.render.databricks

import cc.monomer.metricflow.domain.sql.render.DefaultSqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderer

/**
 * Plan renderer for the Databricks engine.
 *
 * Port of `metricflow.sql.render.databricks.DatabricksSqlPlanRenderer`. Plan-level
 * rendering is identical to ANSI; this class only swaps the expression renderer.
 */
open class DatabricksSqlPlanRenderer : DefaultSqlPlanRenderer() {

    override val exprRenderer: SqlExpressionRenderer get() = EXPR_RENDERER

    companion object {
        /** Shared singleton expression renderer — matches Python's `EXPR_RENDERER` constant. */
        val EXPR_RENDERER: SqlExpressionRenderer = DatabricksSqlExpressionRenderer()
    }
}
