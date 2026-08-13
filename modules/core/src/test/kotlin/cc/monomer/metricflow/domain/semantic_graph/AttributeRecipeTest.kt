package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipe
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttributeRecipeTest {

    @Test
    fun `EMPTY constant has empty fields`() {
        val recipe = AttributeRecipe.EMPTY
        assertTrue(recipe.indexedDunderName.isEmpty())
        assertTrue(recipe.joinedModelIds.isEmpty())
        assertTrue(recipe.elementProperties.isEmpty())
        assertEquals(null, recipe.elementType)
    }

    @Test
    fun `create from initial step seeds fields correctly`() {
        val step = AttributeRecipeStep.EMPTY.copy(
            addDunderNameElement = "ds",
            addEntityLink = "user",
            addModelJoin = SemanticModelId.getInstance("bookings_source"),
            setElementType = LinkableElementType.TIME_DIMENSION,
        )
        val recipe = AttributeRecipe.create(step)
        assertEquals(listOf("ds"), recipe.indexedDunderName)
        assertEquals(listOf("user"), recipe.entityLinkNames)
        assertEquals(listOf(SemanticModelId.getInstance("bookings_source")), recipe.joinedModelIds)
        assertEquals(LinkableElementType.TIME_DIMENSION, recipe.elementType)
    }

    @Test
    fun `appendStep appends to the end`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(addDunderNameElement = "user"),
        ).appendStep(
            AttributeRecipeStep.EMPTY.copy(addDunderNameElement = "country"),
        )
        assertEquals(listOf("user", "country"), recipe.indexedDunderName)
    }

    @Test
    fun `pushStep prepends to the start`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(addDunderNameElement = "country"),
        ).pushStep(
            AttributeRecipeStep.EMPTY.copy(addDunderNameElement = "user"),
        )
        assertEquals(listOf("user", "country"), recipe.indexedDunderName)
    }

    @Test
    fun `resolveCompleteProperties marks LOCAL with single joined model`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "country",
                addModelJoin = SemanticModelId.getInstance("listings_source"),
                setElementType = LinkableElementType.DIMENSION,
            ),
        )
        val properties = recipe.resolveCompleteProperties()
        assertTrue(GroupByItemProperty.LOCAL in properties)
    }

    @Test
    fun `resolveCompleteProperties marks JOINED with two models`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "country",
                addModelJoin = SemanticModelId.getInstance("a"),
                setElementType = LinkableElementType.DIMENSION,
            ),
        ).appendStep(AttributeRecipeStep.EMPTY.copy(addModelJoin = SemanticModelId.getInstance("b")))
        val properties = recipe.resolveCompleteProperties()
        assertTrue(GroupByItemProperty.JOINED in properties)
    }

    @Test
    fun `resolveCompleteProperties marks MULTI_HOP with three models`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "country",
                addModelJoin = SemanticModelId.getInstance("a"),
                setElementType = LinkableElementType.DIMENSION,
            ),
        ).appendStep(AttributeRecipeStep.EMPTY.copy(addModelJoin = SemanticModelId.getInstance("b")))
            .appendStep(AttributeRecipeStep.EMPTY.copy(addModelJoin = SemanticModelId.getInstance("c")))
        val properties = recipe.resolveCompleteProperties()
        assertTrue(GroupByItemProperty.JOINED in properties)
        assertTrue(GroupByItemProperty.MULTI_HOP in properties)
    }

    @Test
    fun `resolveCompleteProperties detects DERIVED_TIME_GRANULARITY when grains differ`() {
        val recipe = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "ds",
                addModelJoin = SemanticModelId.getInstance("bookings"),
                setElementType = LinkableElementType.TIME_DIMENSION,
                setSourceTimeGrain = TimeGranularity.DAY,
                setTimeGrainAccess = ExpandedTimeGranularity("month", TimeGranularity.MONTH),
            ),
        )
        val properties = recipe.resolveCompleteProperties()
        assertTrue(GroupByItemProperty.DERIVED_TIME_GRANULARITY in properties)
    }

    @Test
    fun `resolveElementName branches on element type`() {
        val timeDim = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "ds",
                setElementType = LinkableElementType.TIME_DIMENSION,
            ),
        ).appendStep(AttributeRecipeStep.EMPTY.copy(addDunderNameElement = "month"))
        assertEquals("ds", timeDim.resolveElementName())

        val dimension = AttributeRecipe.create(
            AttributeRecipeStep.EMPTY.copy(
                addDunderNameElement = "country",
                setElementType = LinkableElementType.DIMENSION,
            ),
        )
        assertEquals("country", dimension.resolveElementName())
    }

    @Test
    fun `resolveCompleteProperties throws when element type is missing`() {
        val recipe = AttributeRecipe.EMPTY
        assertFailsWith<IllegalStateException> { recipe.resolveCompleteProperties() }
    }
}
