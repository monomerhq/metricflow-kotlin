package cc.monomer.metricflow.domain.dataflow.validation

import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.EntitySpec
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class JoinDataflowOutputValidatorTest {

    private fun manifest(): SemanticManifest = SemanticManifest(
        semanticModels = listOf(
            SemanticModel(
                name = "users_source",
                nodeRelation = NodeRelation(alias = "users", schemaName = "demo", relationName = "demo.users"),
                primaryEntity = null,
                entities = listOf(Entity(name = "user", type = EntityType.PRIMARY)),
            ),
            SemanticModel(
                name = "orders_source",
                nodeRelation = NodeRelation(alias = "orders", schemaName = "demo", relationName = "demo.orders"),
                primaryEntity = null,
                entities = listOf(
                    Entity(name = "order", type = EntityType.PRIMARY),
                    Entity(name = "user", type = EntityType.FOREIGN),
                ),
            ),
        ),
        metrics = emptyList(),
        projectConfiguration = ProjectConfiguration(
            timeSpines = listOf(
                TimeSpine(
                    nodeRelation = NodeRelation(alias = "time_spine", schemaName = "demo", relationName = "demo.time_spine"),
                    primaryColumn = TimeSpinePrimaryColumn(name = "ds", timeGranularity = TimeGranularity.DAY),
                ),
            ),
        ),
    )

    private fun entityInstance(modelName: String, entityName: String): EntityInstance =
        EntityInstance(
            associatedColumns = listOf(ColumnAssociation.ofSingle(entityName)),
            definedFrom = listOf(
                SemanticModelElementReference(semanticModelName = modelName, elementName = entityName),
            ),
            spec = EntitySpec(elementName = entityName, entityLinks = emptyList(), alias = null),
        )

    @Test
    fun `valid join between FOREIGN and PRIMARY entities`() {
        val manifestLookup = SemanticManifestLookup(manifest())
        val validator = JoinDataflowOutputValidator(manifestLookup.semanticModelLookup)

        val leftSet = InstanceSet(
            simpleMetricInputInstances = emptyList(),
            dimensionInstances = emptyList(),
            timeDimensionInstances = emptyList(),
            entityInstances = listOf(entityInstance("orders_source", "user")),
            groupByMetricInstances = emptyList(),
            metricInstances = emptyList(),
            metadataInstances = emptyList(),
        )
        val rightSet = InstanceSet(
            simpleMetricInputInstances = emptyList(),
            dimensionInstances = emptyList(),
            timeDimensionInstances = emptyList(),
            entityInstances = listOf(entityInstance("users_source", "user")),
            groupByMetricInstances = emptyList(),
            metricInstances = emptyList(),
            metadataInstances = emptyList(),
        )

        val result = validator.isValidInstanceSetJoin(
            leftInstanceSet = leftSet,
            rightInstanceSet = rightSet,
            onEntityReference = EntityReference(elementName = "user"),
        )
        assertTrue(result)
    }
}
