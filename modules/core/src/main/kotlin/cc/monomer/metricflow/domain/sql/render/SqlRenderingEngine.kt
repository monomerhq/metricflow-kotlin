package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Minimal engine-feature contract consulted by [DefaultSqlExpressionRenderer] when deciding
 * whether an expression can be rendered for the target dialect.
 *
 * Port of the small subset of `metricflow.protocols.sql_client.SqlEngine` that the expression
 * renderer actually depends on:
 *
 * - [name] — used in [cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError]
 *   messages.
 * - [unsupportedGranularities] — `DATE_TRUNC` rejects these granularities up front.
 *
 * The full `SqlEngine` enum lives in `:domain:sqlclient` (W8); declaring it as a small
 * port-style interface here lets dialect renderers (W6) supply their own implementations
 * without `:domain:sql:render` having a forward dependency on that wave.
 */
interface SqlRenderingEngine {
    /** Engine display name — e.g. `"BIGQUERY"`. */
    val name: String

    /** Time-granularity values rejected by this engine's `DATE_TRUNC` implementation. */
    val unsupportedGranularities: Set<TimeGranularity>
}
