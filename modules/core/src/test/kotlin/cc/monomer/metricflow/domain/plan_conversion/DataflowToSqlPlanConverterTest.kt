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
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.sql.optimizer.SqlOptimizationLevel
import cc.monomer.metricflow.domain.sql.render.SqlEngine
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
        // W14-deferred visitors still raise NotImplementedError.
        assertFailsWith<NotImplementedError> {
            visitor.visitJoinOverTimeRangeNode(
                node = cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode(
                    parentNode = fakeParentNode(),
                    queriedAggTimeDimensionSpecs = emptyList(),
                    window = null,
                    grainToDate = null,
                    timeRangeConstraint = null,
                ),
            )
        }
        // Reference MetricSpec to keep the import warm and confirm spec wiring builds.
        @Suppress("UnusedExpression") MetricSpec.fromElementName("x")

        // Reference SqlEngine + SqlOptimizationLevel to keep the import graph warm.
        @Suppress("UnusedExpression") SqlEngine.BIGQUERY
        @Suppress("UnusedExpression") SqlOptimizationLevel.DEFAULT_LEVEL
    }

    private fun fakeParentNode(): cc.monomer.metricflow.domain.dataflow.DataflowPlanNode =
        cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode(
            dataSet = object : cc.monomer.metricflow.domain.dataflow.support.SqlDataSet {
                override val semanticModelReference: cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference? = null
            },
        )
}
