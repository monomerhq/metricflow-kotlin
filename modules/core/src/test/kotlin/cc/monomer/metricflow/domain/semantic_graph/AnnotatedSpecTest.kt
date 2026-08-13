package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AnnotatedSpecTest {

    @Test
    fun `dimension annotated spec resolves to DimensionSpec`() {
        val annotated = AnnotatedSpec.create(
            elementType = LinkableElementType.DIMENSION,
            elementName = "country",
            properties = listOf(GroupByItemProperty.LOCAL),
            originModelIds = listOf(SemanticModelId.getInstance("listings_source")),
            derivedFromSemanticModels = emptyList(),
            entityLinks = listOf(EntityReference("listing")),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        val spec = annotated.spec
        assertIs<DimensionSpec>(spec)
        assertEquals("country", spec.elementName)
        assertEquals(listOf(EntityReference("listing")), spec.entityLinks)
    }

    @Test
    fun `time dimension annotated spec resolves to TimeDimensionSpec`() {
        val annotated = AnnotatedSpec.create(
            elementType = LinkableElementType.TIME_DIMENSION,
            elementName = "ds",
            properties = emptyList(),
            originModelIds = listOf(SemanticModelId.getInstance("bookings")),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = ExpandedTimeGranularity("month", TimeGranularity.MONTH),
            datePart = null,
        )
        val spec = annotated.spec
        assertIs<TimeDimensionSpec>(spec)
        assertEquals("ds", spec.elementName)
        assertEquals(ExpandedTimeGranularity("month", TimeGranularity.MONTH), spec.timeGranularity)
    }

    @Test
    fun `entity annotated spec resolves to EntitySpec`() {
        val annotated = AnnotatedSpec.create(
            elementType = LinkableElementType.ENTITY,
            elementName = "user_id",
            properties = listOf(GroupByItemProperty.ENTITY),
            originModelIds = listOf(SemanticModelId.getInstance("users")),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        val spec = annotated.spec
        assertIs<EntitySpec>(spec)
        assertEquals("user_id", spec.elementName)
    }

    @Test
    fun `merge combines properties and origin models`() {
        val a = AnnotatedSpec.create(
            elementType = LinkableElementType.DIMENSION,
            elementName = "country",
            properties = listOf(GroupByItemProperty.LOCAL),
            originModelIds = listOf(SemanticModelId.getInstance("a")),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        val b = AnnotatedSpec.create(
            elementType = LinkableElementType.DIMENSION,
            elementName = "country",
            properties = listOf(GroupByItemProperty.JOINED),
            originModelIds = listOf(SemanticModelId.getInstance("b")),
            derivedFromSemanticModels = emptyList(),
            entityLinks = emptyList(),
            metricSubqueryEntityLinks = null,
            timeGrain = null,
            datePart = null,
        )
        val merged = a.merge(b)
        assertEquals(2, merged.originSemanticModelNames.size)
        assertEquals(2, merged.elementProperties.size)
    }

    @Test
    fun `merge fails on incompatible specs`() {
        val a = AnnotatedSpec.create(
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
        val b = AnnotatedSpec.create(
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
        assertFailsWith<RuntimeException> { a.merge(b) }
    }
}
