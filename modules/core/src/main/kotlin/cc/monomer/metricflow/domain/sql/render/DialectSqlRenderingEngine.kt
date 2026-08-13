package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine

/**
 * A tiny, dialect-agnostic [SqlRenderingEngine] implementation that dialect modules use to
 * wire up the engine-feature data their [cc.monomer.metricflow.domain.sql.render
 * .DefaultSqlExpressionRenderer] consults when validating granularities.
 *
 * The Python equivalent is the per-dialect `SqlEngine` enum value (e.g.
 * `SqlEngine.TRINO`). Because the full `SqlEngine` enum lives in `:domain:sqlclient` (W8)
 * and would force a forward dependency, W6 dialects instead pass a [SqlRenderingEngine]
 * — and this data class is the canonical implementation of that port for any dialect
 * that doesn't need anything fancier.
 *
 * Each dialect module declares a `companion object` constant (e.g. `TrinoEngine`) of this
 * type and feeds it to its expression renderer via the `sqlEngine` property override.
 */
data class DialectSqlRenderingEngine(
    override val name: String,
    override val unsupportedGranularities: Set<TimeGranularity>,
) : SqlRenderingEngine
