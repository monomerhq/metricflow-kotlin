package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlEngineTest {

    @Test
    fun `every variant carries the canonical display name`() {
        assertEquals("BigQuery", SqlEngine.BIGQUERY.displayName)
        assertEquals("DuckDB", SqlEngine.DUCKDB.displayName)
        assertEquals("Redshift", SqlEngine.REDSHIFT.displayName)
        assertEquals("Postgres", SqlEngine.POSTGRES.displayName)
        assertEquals("Snowflake", SqlEngine.SNOWFLAKE.displayName)
        assertEquals("Databricks", SqlEngine.DATABRICKS.displayName)
        assertEquals("Trino", SqlEngine.TRINO.displayName)
    }

    @Test
    fun `unsupported granularities match Python parity`() {
        // Python parity per metricflow.protocols.sql_client.SqlEngine.unsupported_granularities.
        assertEquals(emptySet<TimeGranularity>(), SqlEngine.SNOWFLAKE.unsupportedGranularities)
        assertEquals(setOf(TimeGranularity.NANOSECOND), SqlEngine.BIGQUERY.unsupportedGranularities)
        assertEquals(setOf(TimeGranularity.NANOSECOND), SqlEngine.DATABRICKS.unsupportedGranularities)
        assertEquals(setOf(TimeGranularity.NANOSECOND), SqlEngine.DUCKDB.unsupportedGranularities)
        assertEquals(setOf(TimeGranularity.NANOSECOND), SqlEngine.POSTGRES.unsupportedGranularities)
        assertEquals(setOf(TimeGranularity.NANOSECOND), SqlEngine.REDSHIFT.unsupportedGranularities)
        assertEquals(
            setOf(TimeGranularity.NANOSECOND, TimeGranularity.MICROSECOND),
            SqlEngine.TRINO.unsupportedGranularities,
        )
    }

    @Test
    fun `every variant implements SqlRenderingEngine`() {
        for (engine in SqlEngine.entries) {
            val rendering: SqlRenderingEngine = engine
            assertTrue(rendering.name.isNotEmpty())
        }
    }

    @Test
    fun `SqlRenderingEngine name matches Kotlin enum name`() {
        assertEquals("BIGQUERY", SqlEngine.BIGQUERY.name)
        assertEquals("TRINO", SqlEngine.TRINO.name)
    }

    @Test
    fun `Trino additionally rejects microsecond granularity`() {
        assertTrue(TimeGranularity.MICROSECOND in SqlEngine.TRINO.unsupportedGranularities)
        assertFalse(TimeGranularity.MICROSECOND in SqlEngine.BIGQUERY.unsupportedGranularities)
    }
}
