package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DimensionLookupTest {

    @Test
    fun `getInvariant returns dimension type and partition flag`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = null,
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(
                Dimension(name = "country", type = DimensionType.CATEGORICAL),
                Dimension(name = "ds", type = DimensionType.TIME, typeParams = DimensionTypeParams(TimeGranularity.DAY)),
            ),
        )
        val lookup = DimensionLookup(listOf(model))
        val invariant = lookup.getInvariant(DimensionReference("country"))
        assertEquals(DimensionType.CATEGORICAL, invariant.dimensionType)
        assertEquals(false, invariant.isPartition)
    }

    @Test
    fun `unknown dimension throws`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = null,
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = emptyList(),
        )
        val lookup = DimensionLookup(listOf(model))
        assertFailsWith<IllegalArgumentException> { lookup.getInvariant(DimensionReference("ghost")) }
    }

    @Test
    fun `conflicting dimension definitions throw`() {
        val model1 = SemanticModel(
            name = "m1",
            nodeRelation = NodeRelation(alias = "a1", schemaName = "s", relationName = "s.a1"),
            primaryEntity = null,
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(
                Dimension(name = "country", type = DimensionType.CATEGORICAL, isPartition = false),
            ),
        )
        val model2 = SemanticModel(
            name = "m2",
            nodeRelation = NodeRelation(alias = "a2", schemaName = "s", relationName = "s.a2"),
            primaryEntity = null,
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(
                Dimension(name = "country", type = DimensionType.CATEGORICAL, isPartition = true),
            ),
        )
        assertFailsWith<IllegalStateException> { DimensionLookup(listOf(model1, model2)) }
    }

    @Test
    fun `dimensionsByDunderName uses primary-entity prefix`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = null,
            entities = listOf(Entity(name = "listing", type = EntityType.PRIMARY)),
            dimensions = listOf(Dimension(name = "country", type = DimensionType.CATEGORICAL)),
        )
        val lookup = DimensionLookup(listOf(model))
        assertEquals(setOf("listing__country"), lookup.dimensionsByDunderName.keys)
    }
}
