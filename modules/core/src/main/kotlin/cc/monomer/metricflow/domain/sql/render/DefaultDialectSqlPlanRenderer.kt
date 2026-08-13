package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.sql.render.DefaultSqlPlanRenderer

/**
 * The "default" dialect renderer — pure ANSI SQL, no engine-specific overrides.
 *
 * Python's `metricflow.sql.render.sql_plan_renderer.DefaultSqlPlanRenderer` plays two
 * roles: it's both the base class every dialect extends AND the renderer used when no
 * dialect is specified (e.g. by `SqlEngine.DUCKDB` test fixtures that fall back to ANSI).
 * The base-class role lives in `:domain:sql:render` (W5); this module re-exposes it as a
 * named dialect renderer so the engine facade (W10) can resolve "default" through the
 * same lookup path as every other dialect.
 *
 * Inheriting (rather than typealiasing) ensures dialect dispatch by class identity works
 * symmetrically with the other dialect renderers.
 */
open class DefaultDialectSqlPlanRenderer : DefaultSqlPlanRenderer()
