package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GroupByItemSetTest {

    private fun annotated(
        elementName: String,
        properties: List<GroupByItemProperty> = listOf(GroupByItemProperty.LOCAL),
        entityLinks: List<EntityReference> = emptyList(),
    ): AnnotatedSpec = AnnotatedSpec.create(
        elementType = LinkableElementType.DIMENSION,
        elementName = elementName,
        properties = properties,
        originModelIds = listOf(SemanticModelId.getInstance("bookings_source")),
        derivedFromSemanticModels = emptyList(),
        entityLinks = entityLinks,
        metricSubqueryEntityLinks = null,
        timeGrain = null,
        datePart = null,
    )

    @Test
    fun `EMPTY set is empty`() {
        assertTrue(GroupByItemSet.EMPTY.isEmpty)
    }

    @Test
    fun `union merges annotated specs by dunder name`() {
        val a = GroupByItemSet.create(annotated("country"))
        val b = GroupByItemSet.create(annotated("country"))
        val unioned = a.union(b)
        assertEquals(1, unioned.annotatedSpecs.size)
    }

    @Test
    fun `intersection retains only common dunder names`() {
        val a = GroupByItemSet.create(annotated("country"), annotated("category"))
        val b = GroupByItemSet.create(annotated("country"))
        val intersected = a.intersection(b)
        assertEquals(setOf("country"), intersected.annotatedSpecs.map { it.elementName }.toSet())
    }

    @Test
    fun `filter denies properties in the denylist`() {
        val set = GroupByItemSet.create(
            annotated("a", listOf(GroupByItemProperty.LOCAL)),
            annotated("b", listOf(GroupByItemProperty.METRIC)),
        )
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = null,
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = listOf(GroupByItemProperty.METRIC),
        )
        val filtered = set.filter(filter)
        assertEquals(setOf("a"), filtered.annotatedSpecs.map { it.elementName }.toSet())
        assertFalse(filtered.isEmpty)
    }

    @Test
    fun `filter applies element name allowlist`() {
        val set = GroupByItemSet.create(annotated("a"), annotated("b"))
        val filter = GroupByItemSetFilter.create(
            elementNameAllowlist = listOf("a"),
            anyPropertiesAllowlist = null,
            anyPropertiesDenylist = null,
        )
        val filtered = set.filter(filter)
        assertEquals(setOf("a"), filtered.annotatedSpecs.map { it.elementName }.toSet())
    }

    @Test
    fun `dunderNameToAnnotatedSpec maps by spec dunder name`() {
        val set = GroupByItemSet.create(annotated("country"))
        assertTrue("country" in set.dunderNameToAnnotatedSpec.keys)
    }
}
