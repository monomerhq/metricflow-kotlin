package cc.monomer.metricflow.common.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HelpersTest {

    @Test
    fun `assertExactlyOneArgSet enforces exactly one`() {
        assertExactlyOneArgSet("a" to null, "b" to "x", "c" to null)
        assertFailsWith<IllegalStateException> {
            assertExactlyOneArgSet("a" to null, "b" to null)
        }
        assertFailsWith<IllegalStateException> {
            assertExactlyOneArgSet("a" to "x", "b" to "y")
        }
    }

    @Test
    fun `assertAtMostOneArgSet allows zero or one`() {
        assertAtMostOneArgSet("a" to null, "b" to null)
        assertAtMostOneArgSet("a" to "x", "b" to null)
        assertFailsWith<IllegalStateException> {
            assertAtMostOneArgSet("a" to "x", "b" to "y")
        }
    }

    @Test
    fun `mfRandomId default uses 8 chars from the safe alphabet`() {
        val id = mfRandomId()
        assertEquals(8, id.length)
        for (c in id) {
            assertTrue(c !in MF_RANDOM_ID_EXCLUDED_CHARACTERS)
            assertTrue(c.isLowerCase() || c.isDigit())
        }
    }

    @Test
    fun `mfRandomId honours explicit length`() {
        val id = mfRandomId(12, MF_RANDOM_ID_EXCLUDED_CHARACTERS)
        assertEquals(12, id.length)
    }

    @Test
    fun `mfSha1Iterables is deterministic`() {
        val h1 = mfSha1Iterables(listOf("a", "b"), listOf(1, 2))
        val h2 = mfSha1Iterables(listOf("a", "b"), listOf(1, 2))
        assertEquals(h1, h2)
    }

    @Test
    fun `mfFirstNonNull returns first non-null`() {
        assertEquals("x", mfFirstNonNull(null, "x", "y"))
        assertNull(mfFirstNonNull<String>(null, null))
    }

    @Test
    fun `mfFirstNonNullOrRaise throws when all null`() {
        assertFailsWith<IllegalStateException> {
            mfFirstNonNullOrRaise<String>()
        }
    }

    @Test
    fun `mfFirstItem returns first or throws`() {
        assertEquals(1, mfFirstItem(listOf(1, 2, 3)))
        assertFailsWith<NoSuchElementException> {
            mfFirstItem(emptyList<Int>())
        }
    }

    @Test
    fun `mfIndent prefixes every non-empty line`() {
        val input = "line 1\nline 2\n\nline 4"
        val expected = "  line 1\n  line 2\n\n  line 4"
        assertEquals(expected, mfIndent(input))
    }

    @Test
    fun `mfDedent removes common leading whitespace`() {
        val input = "\n    a\n    b\n      c"
        // Common prefix is 4 spaces -> b loses 4, c keeps 2 extra.
        val expected = "a\nb\n  c"
        assertEquals(expected, mfDedent(input))
    }

    @Test
    fun `mfWrap wraps on word boundaries`() {
        val out = mfWrap("aaaa bbbb cccc dddd", width = 9)
        // Expected: "aaaa bbbb\ncccc dddd"
        assertEquals("aaaa bbbb\ncccc dddd", out)
    }
}
