package cc.monomer.metricflow.domain.spec.bind

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SqlBindParameterSetTest {

    @Test
    fun `merging with an empty set returns the other side without copying`() {
        val set = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 1))
        val merged = SqlBindParameterSet.EMPTY.merge(set)
        assertSame(set, merged)
        val mergedBack = set.merge(SqlBindParameterSet.EMPTY)
        assertSame(set, mergedBack)
    }

    @Test
    fun `merging non-conflicting sets concatenates in left-then-right order`() {
        val left = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 1, "b" to "two"))
        val right = SqlBindParameterSet.createFromAnyDict(mapOf("c" to 3.0, "d" to true))
        val merged = left.merge(right)
        assertEquals(listOf("a", "b", "c", "d"), merged.paramItems.map { it.key })
    }

    @Test
    fun `merging duplicate-but-equal keys keeps the left occurrence`() {
        val left = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 1, "b" to 2))
        val right = SqlBindParameterSet.createFromAnyDict(mapOf("b" to 2, "c" to 3))
        val merged = left.merge(right)
        assertEquals(listOf("a", "b", "c"), merged.paramItems.map { it.key })
    }

    @Test
    fun `merging conflicting same-key different-value throws`() {
        val left = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 1))
        val right = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 2))
        assertFailsWith<IllegalStateException> { left.merge(right) }
    }

    @Test
    fun `equality reduces to underlying key-value mapping`() {
        val a = SqlBindParameterSet.createFromAnyDict(mapOf("a" to 1, "b" to 2))
        val b = SqlBindParameterSet.createFromAnyDict(mapOf("b" to 2, "a" to 1))
        assertEquals(a, b)
    }

    @Test
    fun `paramDict exposes underlying primitives in insertion order`() {
        val set = SqlBindParameterSet.createFromAnyDict(mapOf("k1" to 1, "k2" to "x"))
        val dict = set.paramDict
        assertEquals(listOf("k1", "k2"), dict.keys.toList())
        assertEquals(SqlColumnValue.IntValue(1), dict["k1"])
        assertEquals(SqlColumnValue.StringValue("x"), dict["k2"])
    }

    @Test
    fun `bind values lift every supported primitive`() {
        val set = SqlBindParameterSet.createFromAnyDict(
            mapOf(
                "s" to "hello",
                "i" to 7,
                "f" to 1.5,
                "b" to false,
            ),
        )
        assertEquals(4, set.paramItems.size)
        assertTrue(set.paramItems.any { it.value is SqlBindParameterValue.StringValue })
        assertTrue(set.paramItems.any { it.value is SqlBindParameterValue.IntValue })
        assertTrue(set.paramItems.any { it.value is SqlBindParameterValue.FloatValue })
        assertTrue(set.paramItems.any { it.value is SqlBindParameterValue.BoolValue })
    }

    @Test
    fun `unhandled types throw on lift`() {
        assertFailsWith<IllegalArgumentException> {
            SqlBindParameterValue.fromAny(object {})
        }
    }
}
