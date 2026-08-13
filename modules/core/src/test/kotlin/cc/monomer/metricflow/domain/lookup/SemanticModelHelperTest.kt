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
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SemanticModelHelperTest {

    @Test
    fun `resolvedPrimaryEntity prefers explicit primary_entity field`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = "explicit_pk",
            entities = listOf(
                Entity(name = "other", type = EntityType.FOREIGN),
            ),
        )
        assertEquals(EntityReference("explicit_pk"), SemanticModelHelper.resolvedPrimaryEntity(model))
    }

    @Test
    fun `resolvedPrimaryEntity falls back to entity with PRIMARY type`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = null,
            entities = listOf(
                Entity(name = "pk", type = EntityType.PRIMARY),
                Entity(name = "fk", type = EntityType.FOREIGN),
            ),
        )
        assertEquals(EntityReference("pk"), SemanticModelHelper.resolvedPrimaryEntity(model))
    }

    @Test
    fun `resolvedPrimaryEntity throws if no primary entity`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = null,
            entities = listOf(Entity(name = "fk", type = EntityType.FOREIGN)),
        )
        assertFailsWith<IllegalArgumentException> { SemanticModelHelper.resolvedPrimaryEntity(model) }
    }

    @Test
    fun `entityLinksForLocalElements returns sorted, deduplicated entity links`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = "explicit_pk",
            entities = listOf(
                Entity(name = "natural_e", type = EntityType.NATURAL),
                Entity(name = "fk", type = EntityType.FOREIGN),
                Entity(name = "uniq", type = EntityType.UNIQUE),
            ),
        )
        // FOREIGN entities are not linkable; sorted by element name.
        val expected = listOf(
            EntityReference("explicit_pk"),
            EntityReference("natural_e"),
            EntityReference("uniq"),
        ).sortedBy { it.elementName }
        assertEquals(expected, SemanticModelHelper.entityLinksForLocalElements(model))
    }

    @Test
    fun `getTimeDimensionGrains extracts grains from TIME dimensions`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = "pk",
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(
                Dimension(name = "ds", type = DimensionType.TIME, typeParams = DimensionTypeParams(TimeGranularity.DAY)),
                Dimension(name = "ts", type = DimensionType.TIME, typeParams = DimensionTypeParams(TimeGranularity.HOUR)),
                Dimension(name = "country", type = DimensionType.CATEGORICAL),
            ),
        )
        val grains = SemanticModelHelper.getTimeDimensionGrains(model)
        assertEquals(TimeGranularity.DAY, grains[TimeDimensionReference("ds")])
        assertEquals(TimeGranularity.HOUR, grains[TimeDimensionReference("ts")])
        assertEquals(2, grains.size, "Categorical dimensions should not appear")
    }

    @Test
    fun `getDimensionFromSemanticModel finds dimension by element name`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = "pk",
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(
                Dimension(name = "country", type = DimensionType.CATEGORICAL),
            ),
        )
        val dim = SemanticModelHelper.getDimensionFromSemanticModel(model, DimensionReference("country"))
        assertEquals("country", dim.name)
    }

    @Test
    fun `getDimensionFromSemanticModel throws if dimension missing`() {
        val model = SemanticModel(
            name = "m",
            nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
            primaryEntity = "pk",
            entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
            dimensions = listOf(Dimension(name = "country", type = DimensionType.CATEGORICAL)),
        )
        assertFailsWith<IllegalArgumentException> {
            SemanticModelHelper.getDimensionFromSemanticModel(model, DimensionReference("missing"))
        }
    }
}
