package cc.monomer.metricflow.infrastructure.sql.render.postgres

import cc.monomer.metricflow.domain.sql.render.DefaultSqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderer

/**
 * Plan renderer for the PostgreSQL engine.
 *
 * Port of `metricflow.sql.render.postgres.PostgresSQLSqlPlanRenderer` — the Python class
 * name has a doubled `SQL`; we drop the duplicate in Kotlin since it's clearly an
 * upstream typo and the docstring confirms the intent is "Postgres SQL plan renderer".
 * Plan-level rendering is identical to ANSI; this class only swaps the expression
 * renderer.
 */
open class PostgresSqlPlanRenderer : DefaultSqlPlanRenderer() {

    override val exprRenderer: SqlExpressionRenderer get() = EXPR_RENDERER

    companion object {
        /** Shared singleton expression renderer — matches Python's `EXPR_RENDERER` constant. */
        val EXPR_RENDERER: SqlExpressionRenderer = PostgresSqlExpressionRenderer()
    }
}
