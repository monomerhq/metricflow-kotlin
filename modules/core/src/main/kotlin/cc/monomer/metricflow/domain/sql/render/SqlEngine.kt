package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine

/**
 * Enumeration of the SQL engines that MetricFlow can target.
 *
 * Port of `metricflow.protocols.sql_client.SqlEngine`.
 *
 * The Python enum's string values are the canonical engine display names
 * (`"BigQuery"`, `"DuckDB"`, etc.) — they are used by snapshot tests and by
 * test infrastructure to locate dialect-specific fixtures. Kotlin preserves
 * those strings via [displayName].
 *
 * Each variant implements [SqlRenderingEngine] (the W5 interface) so the W6
 * dialect renderers can plug in via the enum directly. The
 * [unsupportedGranularities] set lists granularities that the engine's
 * `DATE_TRUNC` / native `TIMESTAMP` type cannot represent — the expression
 * renderer consults this set when deciding whether a granularity expression
 * can be emitted for the target dialect.
 *
 * The [SqlRenderingEngine.name] contract is satisfied by Kotlin's built-in
 * enum `name` (e.g. `"BIGQUERY"`); the human-readable form is exposed
 * separately as [displayName] to match the Python enum's `.value`.
 */
enum class SqlEngine(
    val displayName: String,
    override val unsupportedGranularities: Set<TimeGranularity>,
) : SqlRenderingEngine {
    BIGQUERY(
        displayName = "BigQuery",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
    ),
    DUCKDB(
        displayName = "DuckDB",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
    ),
    REDSHIFT(
        displayName = "Redshift",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
    ),
    POSTGRES(
        displayName = "Postgres",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
    ),
    SNOWFLAKE(
        displayName = "Snowflake",
        unsupportedGranularities = emptySet(),
    ),
    DATABRICKS(
        displayName = "Databricks",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
    ),
    TRINO(
        displayName = "Trino",
        unsupportedGranularities = setOf(TimeGranularity.NANOSECOND, TimeGranularity.MICROSECOND),
    ),
}
