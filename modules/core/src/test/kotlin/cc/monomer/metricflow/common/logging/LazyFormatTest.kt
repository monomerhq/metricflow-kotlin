package cc.monomer.metricflow.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LazyFormatTest {

    @Test
    fun `lazy format renders title only when no kwargs`() {
        val f = LazyFormat("hello")
        assertEquals("hello", f.toString())
    }

    @Test
    fun `lazy format renders kwargs one per line`() {
        val f = LazyFormat("hello", mapOf("a" to 1, "b" to "x"))
        val out = f.toString()
        assertTrue(out.startsWith("hello"))
        assertTrue(out.contains("a: 1"))
        assertTrue(out.contains("b: 'x'"))
    }

    @Test
    fun `lazy format defers callable kwargs until rendered`() {
        var calls = 0
        val supplier: () -> String = { calls++; "computed" }
        val f = LazyFormat("title", mapOf("k" to supplier))
        assertEquals(0, calls, "lambda must not be evaluated until toString is called")
        f.toString()
        assertEquals(1, calls)
        f.toString()
        assertEquals(1, calls, "result is cached and the lambda is invoked exactly once")
    }
}

class PrettyPrintTest {

    @Test
    fun `mfPformat handles primitives and null`() {
        assertEquals("null", mfPformat(null))
        assertEquals("'hello'", mfPformat("hello"))
        assertEquals("42", mfPformat(42))
    }

    @Test
    fun `mfPformat handles lists and maps recursively`() {
        assertEquals("['a', 'b']", mfPformat(listOf("a", "b")))
        assertEquals("{'k': 1}", mfPformat(mapOf("k" to 1)))
    }

    @Test
    fun `mfPformat delegates to MetricFlowPrettyFormattable`() {
        val obj = object : MetricFlowPrettyFormattable {
            override fun prettyFormat() = "custom"
        }
        assertEquals("custom", mfPformat(obj))
    }
}
