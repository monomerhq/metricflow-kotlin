package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.dataflow.instance.DimensionInstance
import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlDataSetTest {

    private val tableNode = SqlTableNode(SqlTable(schemaName = "demo", tableName = "bookings"))

    private val listingEntityInstance = EntityInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("listing")),
        definedFrom = listOf(SemanticModelElementReference("bookings_source", "listing")),
        spec = EntitySpec(elementName = "listing", entityLinks = emptyList(), alias = null),
    )

    private val isInstantDim = DimensionInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("is_instant")),
        definedFrom = listOf(SemanticModelElementReference("bookings_source", "is_instant")),
        spec = DimensionSpec(elementName = "is_instant", entityLinks = emptyList(), alias = null),
    )

    private val metricTimeDay = TimeDimensionInstance(
        associatedColumns = listOf(ColumnAssociation.ofSingle("metric_time__day")),
        definedFrom = listOf(SemanticModelElementReference("bookings_source", "ds")),
        spec = TimeDimensionSpec(
            elementName = "metric_time",
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        ),
    )

    private val instanceSet = InstanceSet(
        simpleMetricInputInstances = emptyList(),
        dimensionInstances = listOf(isInstantDim),
        timeDimensionInstances = listOf(metricTimeDay),
        entityInstances = listOf(listingEntityInstance),
        groupByMetricInstances = emptyList(),
        metricInstances = emptyList(),
        metadataInstances = emptyList(),
    )

    @Test
    fun `wraps a generic SqlPlanNode`() {
        val ds = SqlDataSet(instanceSet = instanceSet, sqlNode = tableNode)
        assertEquals(tableNode, ds.sqlNode)
        assertFailsWith<IllegalStateException> { ds.checkedSqlSelectNode }
    }

    @Test
    fun `rejects passing both select and arbitrary node`() {
        assertFailsWith<IllegalArgumentException> {
            SqlDataSet(instanceSet = instanceSet, _sqlSelectNode = null, _sqlNode = null)
        }
    }

    @Test
    fun `columnAssociationsForEntity returns the matching column`() {
        val ds = SqlDataSet(instanceSet = instanceSet, sqlNode = tableNode)
        val cols = ds.columnAssociationsForEntity(EntityReference("listing"))
        assertEquals("listing", cols[0].columnName)
    }

    @Test
    fun `columnAssociationForDimension returns the matching column`() {
        val ds = SqlDataSet(instanceSet = instanceSet, sqlNode = tableNode)
        val col = ds.columnAssociationForDimension(
            DimensionSpec(elementName = "is_instant", entityLinks = emptyList(), alias = null),
        )
        assertEquals("is_instant", col.columnName)
    }

    @Test
    fun `metricTimeInstanceForTimeConstraint picks the smallest standard grain`() {
        val ds = SqlDataSet(instanceSet = instanceSet, sqlNode = tableNode)
        val instance = ds.metricTimeInstanceForTimeConstraint
        assertEquals("metric_time", instance.spec.elementName)
        assertEquals(TimeGranularity.DAY, instance.spec.timeGranularity?.baseGranularity)
    }

    @Test
    fun `SemanticModelDataSet surfaces its semantic-model reference`() {
        val select = cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode(
            customDescription = "Source",
            selectColumns = emptyList(),
            fromSource = tableNode,
            fromSourceAlias = "src",
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
        val ds = SemanticModelDataSet(
            semanticModelReference = SemanticModelReference("bookings_source"),
            instanceSet = instanceSet,
            sqlSelectNode = select,
        )
        assertEquals(SemanticModelReference("bookings_source"), ds.semanticModelReference)
        assertEquals(select, ds.checkedSqlSelectNode)
    }
}
