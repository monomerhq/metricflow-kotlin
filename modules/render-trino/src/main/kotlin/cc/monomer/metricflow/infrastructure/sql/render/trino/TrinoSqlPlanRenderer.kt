package cc.monomer.metricflow.infrastructure.sql.render.trino

import cc.monomer.metricflow.domain.sql.render.DefaultSqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderer

/**
 * Plan renderer for the Trino engine.
 *
 * Port of `metricflow.sql.render.trino.TrinoSqlPlanRenderer`. The plan-level behaviour
 * is identical to ANSI; the dialect only diverges at the expression level. This class
 * just swaps in [TrinoSqlExpressionRenderer].
 */
open class TrinoSqlPlanRenderer : DefaultSqlPlanRenderer() {

    override val exprRenderer: SqlExpressionRenderer get() = EXPR_RENDERER

    companion object {
        /** Shared singleton expression renderer — matches Python's `EXPR_RENDERER` constant. */
        val EXPR_RENDERER: SqlExpressionRenderer = TrinoSqlExpressionRenderer()
    }
}
