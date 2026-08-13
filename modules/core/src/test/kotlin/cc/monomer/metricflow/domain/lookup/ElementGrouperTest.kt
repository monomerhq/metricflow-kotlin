package cc.monomer.metricflow.domain.lookup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElementGrouperTest {

    @Test
    fun `addValue accumulates values per key`() {
        val grouper = ElementGrouper<String, Int>()
        grouper.addValue("a", 1)
        grouper.addValue("a", 2)
        grouper.addValue("b", 3)
        assertEquals(listOf(1, 2), grouper.getValues("a"))
        assertEquals(listOf(3), grouper.getValues("b"))
    }

    @Test
    fun `keys reflects insertion order`() {
        val grouper = ElementGrouper<String, Int>()
        grouper.addValue("c", 1)
        grouper.addValue("a", 1)
        grouper.addValue("b", 1)
        assertEquals(listOf("c", "a", "b"), grouper.keys)
    }

    @Test
    fun `getValues throws on unknown key`() {
        val grouper = ElementGrouper<String, Int>()
        assertFailsWith<NoSuchElementException> { grouper.getValues("missing") }
    }
}
