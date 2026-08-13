package cc.monomer.metricflow.domain.plan_conversion.to_sql_plan

import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.nodes.AddGeneratedUuidColumnNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.MinMaxNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.NodeRelation
import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.TimeSpine
import cc.monomer.metricflow.domain.manifest.model.TimeSpinePrimaryColumn
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.OrderBySpec
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the W13-filled visit methods of [DataflowNodeToSqlSubqueryVisitor]. Each test
 * builds a small dataflow plan, runs the visitor, and asserts the resulting SQL plan tree has
 * the expected shape. Diff-against-Python parity comes from the corpus diff-runner once W14
 * wires `MetricFlowQueryParser` + `DataflowPlanBuilder.buildPlan`.
 */
class DataflowNodeToSqlSubqueryVisitorTest {

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

    private fun makeVisitor(): DataflowNodeToSqlSubqueryVisitor = DataflowNodeToSqlSubqueryVisitor(
        columnAssociationResolver = FakeColumnAssociationResolver(),
        semanticManifestLookup = emptyLookup(),
        outputColumnOrderer = null,
    )

    private fun makeOneColumnSourceDataSet(columnName: String, instances: InstanceSet): SqlDataSet {
        val table = SqlTableNode.create(sqlTable = SqlTable.fromString("schema.t"))
        val select = SqlSelectStatementNode.create(
            description = "test source",
            selectColumns = listOf(
                SqlSelectColumn(
                    expr = SqlColumnReferenceExpression.create(
                        colRef = SqlColumnReference(tableAlias = "src", columnName = columnName),
                        shouldRenderTableAlias = true,
                    ),
                    columnAlias = columnName,
                ),
            ),
            fromSource = table,
            fromSourceAlias = "src",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        return SqlDataSet(instanceSet = instances, sqlSelectNode = select)
    }

    @Test
    fun `visitSourceNode emits a fresh copy of the parent select`() {
        val sourceDataSet = makeOneColumnSourceDataSet("ds", InstanceSet.EMPTY)
        val node = ReadSqlSourceNode(dataSet = sourceDataSet)
        val visitor = makeVisitor()
        val result = visitor.visitSourceNode(node)
        assertEquals(sourceDataSet.instanceSet, result.instanceSet)
        // Result must wrap a SqlSelectStatementNode (not a generic SqlPlanNode).
        assertNotNull(result.checkedSqlSelectNode)
    }

    @Test
    fun `visitOrderByLimitNode appends ORDER BY and LIMIT to a fresh SELECT`() {
        val sourceDataSet = makeOneColumnSourceDataSet(
            columnName = "country",
            instances = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = listOf(
                    cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance(
                        associatedColumns = listOf(ColumnAssociation.ofSingle("country")),
                        definedFrom = emptyList(),
                        spec = DimensionSpec(elementName = "country", entityLinks = emptyList(), alias = null),
                    ),
                ),
                timeDimensionInstances = emptyList(),
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
        )
        val parent = ReadSqlSourceNode(dataSet = sourceDataSet)
        val orderBySpec = OrderBySpec(
            instanceSpec = DimensionSpec(elementName = "country", entityLinks = emptyList(), alias = null),
            descending = true,
        )
        val node = OrderByLimitNode(parentNode = parent, orderBySpecs = listOf(orderBySpec), limit = 10)
        val result = makeVisitor().visitOrderByLimitNode(node)
        assertEquals(10, result.checkedSqlSelectNode.limit)
        assertEquals(1, result.checkedSqlSelectNode.orderBys.size)
        assertTrue(result.checkedSqlSelectNode.orderBys[0].desc)
    }

    @Test
    fun `visitConstrainTimeRangeNode applies BETWEEN over the metric_time column`() {
        // Note: a real ConstrainTimeRangeNode needs a parent dataset whose instance set carries
        // a metric_time dimension with a standard granularity. We construct exactly that.
        val metricTimeSpec = cc.monomer.metricflow.domain.dataflow.dataset.DataSet
            .metricTimeDimensionSpec(
                cc.monomer.metricflow.common.time.ExpandedTimeGranularity.fromTimeGranularity(
                    TimeGranularity.DAY,
                ),
            )
        val timeInstance = cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance(
            associatedColumns = listOf(ColumnAssociation.ofSingle("metric_time__day")),
            definedFrom = emptyList(),
            spec = metricTimeSpec,
        )
        val sourceDataSet = makeOneColumnSourceDataSet(
            columnName = "metric_time__day",
            instances = InstanceSet(
                simpleMetricInputInstances = emptyList(),
                dimensionInstances = emptyList(),
                timeDimensionInstances = listOf(timeInstance),
                entityInstances = emptyList(),
                groupByMetricInstances = emptyList(),
                metricInstances = emptyList(),
                metadataInstances = emptyList(),
            ),
        )
        val parent = ReadSqlSourceNode(dataSet = sourceDataSet)
        val constraint = TimeRangeConstraint(
            startTime = LocalDateTime.of(2024, 1, 1, 0, 0),
            endTime = LocalDateTime.of(2024, 1, 31, 0, 0),
        )
        val node = ConstrainTimeRangeNode(parentNode = parent, timeRangeConstraint = constraint)
        val result = makeVisitor().visitConstrainTimeRangeNode(node)
        assertNotNull(result.checkedSqlSelectNode.where)
    }

    @Test
    fun `visitAddGeneratedUuidColumnNode adds a metadata instance and SqlGenerateUuidExpression`() {
        val sourceDataSet = makeOneColumnSourceDataSet("anything", InstanceSet.EMPTY)
        val parent = ReadSqlSourceNode(dataSet = sourceDataSet)
        val node = AddGeneratedUuidColumnNode(parentNode = parent)
        val result = makeVisitor().visitAddGeneratedUuidColumnNode(node)
        assertEquals(1, result.instanceSet.metadataInstances.size)
        assertEquals("mf_internal_uuid", result.instanceSet.metadataInstances[0].spec.elementName)
        // Last select column must be the UUID column.
        val cols = result.checkedSqlSelectNode.selectColumns
        assertTrue(cols.last().expr is cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression)
    }

    @Test
    fun `visitMinMaxNode emits exactly two aggregate select columns`() {
        val sourceDataSet = makeOneColumnSourceDataSet("revenue", InstanceSet.EMPTY)
        val parent = ReadSqlSourceNode(dataSet = sourceDataSet)
        val node = MinMaxNode(parentNode = parent)
        val result = makeVisitor().visitMinMaxNode(node)
        assertEquals(2, result.checkedSqlSelectNode.selectColumns.size)
        assertEquals(2, result.instanceSet.metadataInstances.size)
        assertEquals(0, result.instanceSet.dimensionInstances.size)
    }

    @Test
    fun `visitWriteToResultDataTableNode wraps parent in a passthrough SELECT`() {
        val sourceDataSet = makeOneColumnSourceDataSet("col", InstanceSet.EMPTY)
        val parent = ReadSqlSourceNode(dataSet = sourceDataSet)
        val node = WriteToResultDataTableNode(parentNode = parent)
        val result = makeVisitor().visitWriteToResultDataTableNode(node)
        assertNotNull(result.checkedSqlSelectNode)
    }
}
