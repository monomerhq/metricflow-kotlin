package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.nodes.MinMaxNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Focused shape checks for the upstream CTE conversion path. */
class DataflowNodeToSqlCteVisitorTest {

    private class FakeColumnAssociationResolver : ColumnAssociationResolver {
        override fun resolveSpec(spec: InstanceSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.elementName)

        override fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): ColumnAssociationResolver = this
    }

    @Test
    fun `nested requested branches are emitted as dependency ordered CTEs`() {
        val dimensionSpec = DimensionSpec(
            elementName = "country",
            entityLinks = emptyList(),
            alias = null,
        )
        val sourceInstanceSet = InstanceSet(
            simpleMetricInputInstances = emptyList(),
            dimensionInstances = listOf(
                DimensionInstance(
                    associatedColumns = listOf(ColumnAssociation.ofSingle("country")),
                    definedFrom = emptyList(),
                    spec = dimensionSpec,
                ),
            ),
            timeDimensionInstances = emptyList(),
            entityInstances = emptyList(),
            groupByMetricInstances = emptyList(),
            metricInstances = emptyList(),
            metadataInstances = emptyList(),
        )
        val sourceSelect = SqlSelectStatementNode.create(
            description = "test source",
            selectColumns = listOf(
                SqlSelectColumn(
                    expr = SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(tableAlias = "src", columnName = "country"),
                        shouldRenderTableAlias = true,
                    ),
                    columnAlias = "country",
                ),
            ),
            fromSource = SqlTableNode.create(SqlTable.fromString("schema.bookings")),
            fromSourceAlias = "src",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val source = ReadSqlSourceNode(SqlDataSet(sourceInstanceSet, sourceSelect))
        val minMax = MinMaxNode(source)
        val visitor = DataflowNodeToSqlCteVisitor(
            columnAssociationResolver = FakeColumnAssociationResolver(),
            semanticManifestLookup = emptyLookup(),
            nodesToConvertToCte = setOf(source, minMax),
            outputColumnOrderer = null,
        )

        val result = visitor.getOutputDataSetWithCtes(minMax)
        val outerSelect = result.checkedSqlSelectNode
        val generated = outerSelect.cteSources

        assertEquals(2, generated.size)
        assertEquals("${source.nodeId.idStr}_cte", generated[0].cteAlias)
        assertEquals("${minMax.nodeId.idStr}_cte", generated[1].cteAlias)
        assertEquals(generated[1].cteAlias, outerSelect.fromSourceAlias)
        assertEquals(
            generated[1].cteAlias,
            assertIs<SqlTableNode>(outerSelect.fromSource).sqlTable.tableName,
        )

        val minMaxCteSelect = assertIs<SqlSelectStatementNode>(generated[1].selectStatement)
        val sourceReadSelect = assertIs<SqlSelectStatementNode>(minMaxCteSelect.fromSource)
        assertEquals(
            generated[0].cteAlias,
            assertIs<SqlTableNode>(sourceReadSelect.fromSource).sqlTable.tableName,
        )
    }

    private fun emptyLookup(): SemanticManifestLookup = SemanticManifestLookup(
        semanticManifest = SemanticManifest(
            semanticModels = emptyList(),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(timeSpines = emptyList()),
        ),
    )
}
