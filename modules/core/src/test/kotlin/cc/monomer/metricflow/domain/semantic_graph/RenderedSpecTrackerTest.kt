package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RenderedSpecTrackerTest {

    @Test
    fun `recordRenderedSpec appends in order`() {
        val tracker = RenderedSpecTracker()
        val first = AnnotatedSpec.create(
            elementType = LinkableElementType.DIMENSION,
            elementName = "country",
            properties = emptyList(),
            originModelIds = emptyList(),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        val second = AnnotatedSpec.create(
            elementType = LinkableElementType.DIMENSION,
            elementName = "category",
            properties = emptyList(),
            originModelIds = emptyList(),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        tracker.recordRenderedSpec(first)
        tracker.recordRenderedSpec(second)
        assertEquals(listOf(first, second), tracker.renderedSpecs)
    }

    @Test
    fun `initial tracker is empty`() {
        assertTrue(RenderedSpecTracker().renderedSpecs.isEmpty())
    }
}
