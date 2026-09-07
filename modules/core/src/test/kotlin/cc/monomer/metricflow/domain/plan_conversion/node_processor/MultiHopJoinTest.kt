package cc.monomer.metricflow.domain.plan_conversion.node_processor

import cc.monomer.metricflow.common.errors.FeatureNotSupportedError
import cc.monomer.metricflow.domain.dataflow.dataset.SemanticModelToDataSetConverter
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MultiHopJoinTest {
    private fun model(name: String, entities: List<Entity>, dimensions: List<Dimension>): SemanticModel = SemanticModel(
        name = name,
        nodeRelation = NodeRelation(alias = name, schemaName = "demo", relationName = "demo.$name"),
        entities = entities,
        dimensions = dimensions + listOf(
            Dimension(name = "tenant", type = DimensionType.CATEGORICAL, isPartition = true),
            Dimension(
                name = "ds", type = DimensionType.TIME, isPartition = true,
                typeParams = DimensionTypeParams(TimeGranularity.DAY),
            ),
        ),
    )

    private class Fixture(models: List<SemanticModel>) {
        val manifest = SemanticManifest(semanticModels = models, metrics = emptyList(), projectConfiguration = ProjectConfiguration())
        val lookup = SemanticManifestLookup(manifest)
        val resolver = DunderColumnAssociationResolver(false)
        val converter = SemanticModelToDataSetConverter(resolver, lookup, ManifestObjectLookup(manifest))
        val nodes = models.map { ReadSqlSourceNode(converter.createSqlSourceDataSet(SemanticModelReference(it.name))) }
        val visitor = DataflowNodeToSqlSubqueryVisitor(resolver, lookup, null)
        val processor = PreJoinNodeProcessor(lookup.semanticModelLookup, visitor)
    }

    private fun fixture(customerType: EntityType): Fixture = Fixture(listOf(
        model("bridge", listOf(Entity("account", type = EntityType.PRIMARY), Entity("customer", type = EntityType.FOREIGN)), emptyList()),
        model("customers", listOf(Entity("customer", type = customerType)) +
            if (customerType == EntityType.FOREIGN) listOf(Entity("customer_row", type = EntityType.PRIMARY)) else emptyList(), listOf(
            Dimension("country", type = DimensionType.CATEGORICAL),
            Dimension("segment", type = DimensionType.CATEGORICAL),
        )),
    ))

    private fun spec(name: String): DimensionSpec = DimensionSpec(
        name, listOf(EntityReference("account"), EntityReference("customer")), null,
    )

    @Test
    fun `two-hop candidates preserve partitions output path provenance and deduplicate lineage`() {
        val fixture = fixture(EntityType.PRIMARY)
        val result = fixture.processor.addMultiHopJoins(
            listOf(spec("country"), spec("segment"), spec("country")), fixture.nodes, SqlJoinType.LEFT_OUTER,
        )
        assertEquals(3, result.size)
        assertEquals(fixture.nodes, result.drop(1))
        val join = result.first() as JoinOnEntitiesNode
        assertSame(fixture.nodes.first(), join.leftNode)
        val target = join.joinTargets.single()
        assertEquals(EntityReference("customer"), target.joinOnEntity)
        assertEquals("tenant", target.joinOnPartitionDimensions.single().startNodeDimensionSpec.elementName)
        val timePartition = target.joinOnPartitionTimeDimensions.single()
        assertEquals(TimeGranularity.DAY, timePartition.startNodeTimeDimensionSpec.baseGranularity)
        assertEquals(emptyList(), timePartition.startNodeTimeDimensionSpec.entityLinks)
        assertEquals(emptyList(), timePartition.nodeToJoinTimeDimensionSpec.entityLinks)
        val output = fixture.visitor.getOutputDataSet(join)
        assertTrue(spec("country").withoutFirstEntityLink() in output.instanceSet.specSet.linkableSpecs)
        assertTrue(spec("segment").withoutFirstEntityLink() in output.instanceSet.specSet.linkableSpecs)
        assertEquals(setOf("bridge", "customers"), join.asPlan().sourceSemanticModels.map { it.semanticModelName }.toSet())
    }

    @Test
    fun `foreign right entity cannot introduce fanout`() {
        val fixture = fixture(EntityType.FOREIGN)
        assertEquals(fixture.nodes, fixture.processor.addMultiHopJoins(listOf(spec("country")), fixture.nodes, SqlJoinType.LEFT_OUTER))
    }

    @Test
    fun `same source node is never joined to itself`() {
        val fixture = Fixture(listOf(model(
            "combined", listOf(Entity("account", type = EntityType.PRIMARY), Entity("customer", type = EntityType.UNIQUE)),
            listOf(Dimension("country", type = DimensionType.CATEGORICAL)),
        )))
        assertEquals(fixture.nodes, fixture.processor.addMultiHopJoins(listOf(spec("country")), fixture.nodes, SqlJoinType.LEFT_OUTER))
    }

    @Test
    fun `repeated entity link follows upstream candidate rules without inventing a cycle policy`() {
        val fixture = Fixture(listOf(
            model("first", listOf(Entity("account", type = EntityType.PRIMARY)), emptyList()),
            model("second", listOf(Entity("account", type = EntityType.PRIMARY)), listOf(Dimension("country", type = DimensionType.CATEGORICAL))),
        ))
        val repeated = DimensionSpec("country", listOf(EntityReference("account"), EntityReference("account")), null)
        val result = fixture.processor.addMultiHopJoins(listOf(repeated), fixture.nodes, SqlJoinType.LEFT_OUTER)
        assertEquals(3, result.size)
        assertEquals(EntityReference("account"), (result.first() as JoinOnEntitiesNode).joinTargets.single().joinOnEntity)
    }

    @Test
    fun `more than two hops fails with upstream supported limit`() {
        val fixture = fixture(EntityType.PRIMARY)
        assertFailsWith<FeatureNotSupportedError> {
            fixture.processor.addMultiHopJoins(listOf(spec("country").withEntityPrefix(EntityReference("order"))), fixture.nodes, SqlJoinType.LEFT_OUTER)
        }
    }
}
