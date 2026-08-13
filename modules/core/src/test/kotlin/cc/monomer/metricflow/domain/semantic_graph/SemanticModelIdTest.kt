package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticModelIdTest {

    @Test
    fun `getInstance constructs by name`() {
        val id = SemanticModelId.getInstance("bookings_source")
        assertEquals("bookings_source", id.modelName)
        assertEquals("bookings_source", id.toString())
    }

    @Test
    fun `semanticModelReference bridges to W1 reference type`() {
        val id = SemanticModelId.getInstance("listings_source")
        assertEquals(SemanticModelReference("listings_source"), id.semanticModelReference)
    }

    @Test
    fun `compareTo is lexicographic by model name`() {
        val a = SemanticModelId.getInstance("a")
        val b = SemanticModelId.getInstance("b")
        assertTrue(a < b)
        assertTrue(b > a)
        assertEquals(0, a.compareTo(SemanticModelId.getInstance("a")))
    }

    @Test
    fun `prettyFormat returns model name`() {
        assertEquals("bookings_source", SemanticModelId.getInstance("bookings_source").prettyFormat())
    }
}
