package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer

/**
 * Engine-side port describing the SQL rendering capabilities MetricFlow needs
 * to emit dialect-specific SQL.
 *
 * Port of `metricflow.protocols.sql_client.SqlClient` — **but with all
 * execution methods removed**.
 *
 * ## Why no execution methods
 *
 * Per the project's mission (see `CLAUDE.md` "엔진 인터페이스 표면"),
 * MetricFlow's responsibility ends at SQL generation. Python's `SqlClient`
 * exposes `query`, `execute`, `dry_run`, and `close` — every one of those
 * actually runs SQL against a warehouse. We do not port those methods at
 * all; the interface here declares only the render-side metadata MetricFlow
 * consults while building a SQL plan:
 *
 * - [sqlEngineType] tells the planner which dialect features to use.
 * - [sqlPlanRenderer] is the dialect-specific renderer (the value flips
 *   between W6 dialect renderers).
 * - [renderBindParameterKey] formats a bind-parameter placeholder in the
 *   way the target engine expects.
 *
 * Callers that need to actually execute the rendered SQL must do so outside
 * MetricFlow — for example, the monomer-semantic-service wraps this port with
 * a Spring-managed JDBC adapter.
 */
interface SqlClient {
    /** Enumerated value describing the dialect this client targets. */
    val sqlEngineType: SqlEngine

    /**
     * Dialect-specific SQL plan renderer used to convert MetricFlow's SQL
     * plan into executable SQL.
     *
     * The Python comment on the equivalent property explains the bundling:
     * "This is bundled with the SqlClient partly as a convenience for
     * accessing a single instance of the renderer, and partly due to the
     * close relationship between dialect and engine capabilities."
     */
    val sqlPlanRenderer: SqlPlanRenderer

    /**
     * Wrap a bind-parameter key with the syntax accepted by the engine.
     *
     * For example, MySQL uses `?`, Postgres uses `$1`, BigQuery uses
     * `@key`. The dataflow planner emits bind parameters by name; this
     * method is the dialect's hook for re-formatting that name.
     */
    fun renderBindParameterKey(bindParameterKey: String): String
}
