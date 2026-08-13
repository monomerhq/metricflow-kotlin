package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.errors.InvalidSemanticModelError
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticModelLookupTest {

    @Test
    fun `getByReference returns the matching model`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val model = lookup.getByReference(SemanticModelReference("bookings_source"))
        assertNotNull(model)
        assertEquals("bookings_source", model.name)
    }

    @Test
    fun `unknown reference returns null`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        assertNull(lookup.getByReference(SemanticModelReference("ghost")))
    }

    @Test
    fun `dimension index lists every defining model`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val modelsForDs = lookup.getSemanticModelsForDimension(DimensionReference("ds"))
        // Both fixture models declare `ds`.
        assertEquals(
            setOf("bookings_source", "listings_source"),
            modelsForDs.map { it.name }.toSet(),
        )
    }

    @Test
    fun `entity index lists every defining model`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val modelsForListing = lookup.getSemanticModelsForEntity(EntityReference("listing"))
        assertEquals(
            setOf("bookings_source", "listings_source"),
            modelsForListing.map { it.name }.toSet(),
        )
    }

    @Test
    fun `getEntityInSemanticModel returns the entity if it exists in the named model`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val entity = lookup.getEntityInSemanticModel(
            SemanticModelElementReference.createFromReferences(
                SemanticModelReference("bookings_source"),
                EntityReference("booking"),
            ),
        )
        assertNotNull(entity)
        assertEquals("booking", entity.name)
        assertEquals(EntityType.PRIMARY, entity.type)
    }

    @Test
    fun `getEntityInSemanticModel returns null when entity missing`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val entity = lookup.getEntityInSemanticModel(
            SemanticModelElementReference.createFromReferences(
                SemanticModelReference("bookings_source"),
                EntityReference("ghost"),
            ),
        )
        assertNull(entity)
    }

    @Test
    fun `modelReferenceToModel returns models in name order`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val ordered = lookup.modelReferenceToModel.keys.toList()
        assertEquals(
            listOf(SemanticModelReference("bookings_source"), SemanticModelReference("listings_source")),
            ordered,
        )
    }

    @Test
    fun `dimension and entity reference lists cover all defined elements`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val dimensionNames = lookup.getDimensionReferences().map { it.elementName }.toSet()
        assertTrue("ds" in dimensionNames)
        assertTrue("country" in dimensionNames)

        val entityNames = lookup.getEntityReferences().map { it.elementName }.toSet()
        assertTrue("booking" in entityNames)
        assertTrue("listing" in entityNames)
    }

    @Test
    fun `duplicate semantic model name throws on lookup construction`() {
        val twoModelsSameName = SemanticManifest(
            semanticModels = listOf(
                SemanticModel(
                    name = "dup",
                    nodeRelation = NodeRelation(alias = "a", schemaName = "s", relationName = "s.a"),
                    primaryEntity = "pk",
                    entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
                ),
                SemanticModel(
                    name = "dup",
                    nodeRelation = NodeRelation(alias = "a2", schemaName = "s", relationName = "s.a2"),
                    primaryEntity = "pk",
                    entities = listOf(Entity(name = "pk", type = EntityType.PRIMARY)),
                ),
            ),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(timeSpines = listOf(LookupFixtures.timeSpine())),
        )
        assertFailsWith<InvalidSemanticModelError> {
            SemanticModelLookup(twoModelsSameName, customGranularities = emptyMap())
        }
    }
}
