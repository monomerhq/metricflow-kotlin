package cc.monomer.metricflow.common.dag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SequentialIdGeneratorTest {

    @Test
    fun `static prefix string values match Python`() {
        assertEquals("dfp", StaticIdPrefix.DATAFLOW_PLAN_PREFIX.strValue)
        assertEquals("cr", StaticIdPrefix.SQL_EXPR_COLUMN_REFERENCE_ID_PREFIX.strValue)
        assertEquals("mfd", StaticIdPrefix.MF_DAG.strValue)
    }

    @Test
    fun `sequential ids increment per prefix`() {
        SequentialIdGenerator.reset()
        val a0 = SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG)
        val a1 = SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG)
        assertEquals("mfd_0", a0.strValue)
        assertEquals("mfd_1", a1.strValue)
    }

    @Test
    fun `per-prefix counters are independent`() {
        SequentialIdGenerator.reset()
        val a = SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG)
        val b = SequentialIdGenerator.createNextId(StaticIdPrefix.CTE)
        assertEquals("mfd_0", a.strValue)
        assertEquals("cte_0", b.strValue)
    }

    @Test
    fun `idNumberSpace pins start value and restores on exit`() {
        SequentialIdGenerator.reset()
        SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG) // mfd_0
        SequentialIdGenerator.idNumberSpace(100) {
            val pinned = SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG)
            assertEquals("mfd_100", pinned.strValue)
        }
        val resumed = SequentialIdGenerator.createNextId(StaticIdPrefix.MF_DAG)
        assertEquals("mfd_1", resumed.strValue)
    }

    @Test
    fun `dynamic prefix works alongside static`() {
        val prefix = DynamicIdPrefix("custom")
        SequentialIdGenerator.reset()
        assertEquals("custom_0", SequentialIdGenerator.createNextId(prefix).strValue)
    }

    @Test
    fun `node id is unique per call`() {
        SequentialIdGenerator.reset()
        val a = NodeId.createUnique(StaticIdPrefix.SQL_EXPR_FUNCTION_ID_PREFIX)
        val b = NodeId.createUnique(StaticIdPrefix.SQL_EXPR_FUNCTION_ID_PREFIX)
        assertNotEquals(a, b)
        assertEquals("fnc_0", a.idStr)
        assertEquals("fnc_1", b.idStr)
    }
}
