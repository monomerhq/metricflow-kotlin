package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialectSqlRenderingEngineTest {

    @Test
    fun `exposes name and unsupported granularities as a SqlRenderingEngine`() {
        val engine: SqlRenderingEngine = DialectSqlRenderingEngine(
            name = "EXAMPLE",
            unsupportedGranularities = setOf(TimeGranularity.NANOSECOND),
        )
        assertEquals("EXAMPLE", engine.name)
        assertTrue(TimeGranularity.NANOSECOND in engine.unsupportedGranularities)
        assertTrue(TimeGranularity.DAY !in engine.unsupportedGranularities)
    }

    @Test
    fun `equals compares structurally`() {
        val a = DialectSqlRenderingEngine("X", emptySet())
        val b = DialectSqlRenderingEngine("X", emptySet())
        assertEquals(a, b)
    }
}
