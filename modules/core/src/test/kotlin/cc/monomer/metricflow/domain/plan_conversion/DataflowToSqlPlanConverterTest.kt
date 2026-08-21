package cc.monomer.metricflow.domain.plan_conversion

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import kotlin.test.Test
import kotlin.test.assertNotNull

class DataflowToSqlPlanConverterTest {

    private class FakeColumnAssociationResolver : ColumnAssociationResolver {
        override fun resolveSpec(spec: InstanceSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.elementName)

        override fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): ColumnAssociationResolver =
            this
    }

    private fun emptyLookup(): SemanticManifestLookup = SemanticManifestLookup(
        semanticManifest = SemanticManifest(
            semanticModels = emptyList(),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(
                timeSpines = listOf(
                    TimeSpine(
                        nodeRelation = NodeRelation(
                            alias = "mf_time_spine",
                            schemaName = "metrics",
                            relationName = "metrics.mf_time_spine",
                        ),
                        primaryColumn = TimeSpinePrimaryColumn(
                            name = "ds",
                            timeGranularity = TimeGranularity.DAY,
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `converter constructs and exposes column association resolver`() {
        val converter = DataflowToSqlPlanConverter(
            columnAssociationResolver = FakeColumnAssociationResolver(),
            semanticManifestLookup = emptyLookup(),
        )
        assertNotNull(converter.columnAssociationResolver)
    }

    @Test
    fun `DataflowNodeToSqlSubqueryVisitor constructs with stable public surface`() {
        val visitor = DataflowNodeToSqlSubqueryVisitor(
            columnAssociationResolver = FakeColumnAssociationResolver(),
            semanticManifestLookup = emptyLookup(),
            outputColumnOrderer = null,
        )
        assertNotNull(visitor)
    }
}
