package cc.monomer.metricflow.domain.dataflow

import cc.monomer.metricflow.domain.dataflow.nodes.AddGeneratedUuidColumnNode
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.AliasSpecsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinConversionEventsNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToTimeSpineNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.MinMaxNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetBaseGrainByCustomGrainNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.SemiAdditiveJoinNode
import cc.monomer.metricflow.domain.dataflow.nodes.SpecToAlias
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.dataflow.nodes.WindowReaggregationNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.dataflow.support.SqlDataSet
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.OrderBySpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private object FakeSqlDataSet : SqlDataSet {
    override val semanticModelReference: SemanticModelReference =
        SemanticModelReference("listings_source")
}

/**
 * Build a minimal but distinct instance of every concrete node variant. The fixtures only
 * need to exercise visitor dispatch and `functionallyIdentical` — they do not need to
 * resemble realistic plans.
 */
private fun allNodeFixtures(): List<DataflowPlanNode> {
    val source = ReadSqlSourceNode(FakeSqlDataSet)
    val metricTimeRef = TimeDimensionReference("ds")
    val tdSpec = TimeDimensionSpec(
        elementName = "ds",
        entityLinks = emptyList(),
        timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
        datePart = null,
        aggregationState = null,
        windowFunctions = emptyList(),
        alias = null,
    )
    val metricSpec = MetricSpec.fromElementName("revenue")
    val computeMetrics = ComputeMetricsNode(
        parentNode = source,
        computedMetricSpecs = listOf(metricSpec),
        passthroughMetricSpecs = emptyList(),
        outputGroupByMetricInstances = false,
        aggregatedToElementsList = emptyList(),
    )
    val orderByNode = OrderByLimitNode(
        parentNode = source,
        orderBySpecs = listOf(OrderBySpec(metricSpec, descending = false)),
        limit = 10,
    )
    val joinDesc = JoinDescription(
        joinNode = source,
        joinOnEntity = EntityReference("listing"),
        joinType = SqlJoinType.INNER,
        joinOnPartitionDimensions = emptyList(),
        joinOnPartitionTimeDimensions = emptyList(),
        validityWindow = null,
    )
    return listOf(
        source,
        JoinOnEntitiesNode(leftNode = source, joinTargets = listOf(joinDesc)),
        AggregateSimpleMetricInputsNode(parentNode = source, nullFillValueMapping = NullFillValueMapping.EMPTY),
        computeMetrics,
        WindowReaggregationNode(
            parentNode = computeMetrics,
            metricSpec = metricSpec,
            orderBySpec = tdSpec,
            partitionBySpecs = emptyList(),
        ),
        orderByNode,
        WhereFilterNode(parentNode = source, filterSpecs = emptyList(), alwaysApply = false),
        WriteToResultDataTableNode(source),
        WriteToResultTableNode(source, outputSqlTable = SqlTable.fromString("schema.t")),
        SelectorNode(
            parentNode = source,
            includeSpecs = InstanceSpecSet.EMPTY,
            replaceDescription = null,
            distinct = false,
        ),
        CombineAggregatedOutputsNode(parentNodes = listOf(source, computeMetrics)),
        ConstrainTimeRangeNode(
            parentNode = source,
            timeRangeConstraint = cc.monomer.metricflow.common.time.TimeRangeConstraint(
                startTime = java.time.LocalDateTime.of(2024, 1, 1, 0, 0),
                endTime = java.time.LocalDateTime.of(2024, 12, 31, 0, 0),
            ),
        ),
        JoinOverTimeRangeNode(
            parentNode = source,
            queriedAggTimeDimensionSpecs = listOf(tdSpec),
            window = null,
            grainToDate = null,
            timeRangeConstraint = null,
        ),
        SemiAdditiveJoinNode(
            parentNode = source,
            entityReferences = listOf(EntityReference("listing")),
            timeDimensionSpec = tdSpec,
            aggByFunction = AggregationType.MAX,
            queriedTimeDimensionSpec = null,
        ),
        MetricTimeDimensionTransformNode(parentNode = source, aggregationTimeDimensionReference = metricTimeRef),
        JoinToTimeSpineNode(
            metricSourceNode = source,
            timeSpineNode = source,
            requestedAggTimeDimensionSpecs = listOf(tdSpec),
            joinOnTimeDimensionSpec = tdSpec,
            joinType = SqlJoinType.LEFT_OUTER,
            standardOffsetWindow = null,
            offsetToGrain = null,
        ),
        MinMaxNode(source),
        AddGeneratedUuidColumnNode(source),
        JoinConversionEventsNode(
            baseNode = source,
            baseTimeDimensionSpec = tdSpec,
            conversionNode = source,
            conversionInputMetricSpec = SimpleMetricInputSpec(elementName = "buys", fillNullsWith = null),
            conversionTimeDimensionSpec = tdSpec,
            uniqueIdentifierKeys = emptyList(),
            entitySpec = EntitySpec(elementName = "listing", entityLinks = emptyList(), alias = null),
            window = null,
            constantProperties = null,
        ),
        JoinToCustomGranularityNode(
            parentNode = source,
            timeDimensionSpec = TimeDimensionSpec(
                elementName = "ds",
                entityLinks = emptyList(),
                timeGranularity = ExpandedTimeGranularity(name = "fiscal_quarter", baseGranularity = TimeGranularity.DAY),
                datePart = null,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            ),
        ),
        AliasSpecsNode(
            parentNode = source,
            changeSpecs = listOf(SpecToAlias(metricSpec, metricSpec.copy(alias = "rev"))),
        ),
        OffsetBaseGrainByCustomGrainNode(
            timeSpineNode = source,
            offsetWindow = TimeWindow(count = 1, granularity = "fiscal_quarter"),
            requiredTimeSpineSpecs = emptyList(),
        ),
        OffsetCustomGranularityNode(
            timeSpineNode = source,
            offsetWindow = TimeWindow(count = 1, granularity = "fiscal_quarter"),
            requiredTimeSpineSpecs = emptyList(),
        ),
    )
}

/**
 * A visitor that simply returns the class name of the visited node. Used to assert that the
 * accept dispatch routes to the correct visitor method for every concrete node — if any node
 * accidentally dispatches to the wrong method (or no method), this catches it.
 */
private class ClassNameVisitor : DataflowPlanNodeVisitor<String> {
    override fun visitSourceNode(node: ReadSqlSourceNode) = "ReadSqlSourceNode"
    override fun visitJoinOnEntitiesNode(node: JoinOnEntitiesNode) = "JoinOnEntitiesNode"
    override fun visitAggregateSimpleMetricInputsNode(node: AggregateSimpleMetricInputsNode) =
        "AggregateSimpleMetricInputsNode"
    override fun visitComputeMetricsNode(node: ComputeMetricsNode) = "ComputeMetricsNode"
    override fun visitWindowReaggregationNode(node: WindowReaggregationNode) = "WindowReaggregationNode"
    override fun visitOrderByLimitNode(node: OrderByLimitNode) = "OrderByLimitNode"
    override fun visitWhereConstraintNode(node: WhereFilterNode) = "WhereFilterNode"
    override fun visitWriteToResultDataTableNode(node: WriteToResultDataTableNode) =
        "WriteToResultDataTableNode"
    override fun visitWriteToResultTableNode(node: WriteToResultTableNode) = "WriteToResultTableNode"
    override fun visitSelectorNode(node: SelectorNode) = "SelectorNode"
    override fun visitCombineAggregatedOutputsNode(node: CombineAggregatedOutputsNode) =
        "CombineAggregatedOutputsNode"
    override fun visitConstrainTimeRangeNode(node: ConstrainTimeRangeNode) = "ConstrainTimeRangeNode"
    override fun visitJoinOverTimeRangeNode(node: JoinOverTimeRangeNode) = "JoinOverTimeRangeNode"
    override fun visitSemiAdditiveJoinNode(node: SemiAdditiveJoinNode) = "SemiAdditiveJoinNode"
    override fun visitMetricTimeDimensionTransformNode(node: MetricTimeDimensionTransformNode) =
        "MetricTimeDimensionTransformNode"
    override fun visitJoinToTimeSpineNode(node: JoinToTimeSpineNode) = "JoinToTimeSpineNode"
    override fun visitMinMaxNode(node: MinMaxNode) = "MinMaxNode"
    override fun visitAddGeneratedUuidColumnNode(node: AddGeneratedUuidColumnNode) =
        "AddGeneratedUuidColumnNode"
    override fun visitJoinConversionEventsNode(node: JoinConversionEventsNode) = "JoinConversionEventsNode"
    override fun visitJoinToCustomGranularityNode(node: JoinToCustomGranularityNode) =
        "JoinToCustomGranularityNode"
    override fun visitAliasSpecsNode(node: AliasSpecsNode) = "AliasSpecsNode"
    override fun visitOffsetBaseGrainByCustomGrainNode(node: OffsetBaseGrainByCustomGrainNode) =
        "OffsetBaseGrainByCustomGrainNode"
    override fun visitOffsetCustomGranularityNode(node: OffsetCustomGranularityNode) =
        "OffsetCustomGranularityNode"
}

class DataflowPlanNodeStructureTest {

    @Test
    fun `every concrete node dispatches to its matching visitor method`() {
        val visitor = ClassNameVisitor()
        for (node in allNodeFixtures()) {
            val expected = node::class.simpleName
            assertEquals(expected, node.accept(visitor), "Bad dispatch for $expected")
        }
    }

    @Test
    fun `node fixtures cover all 23 concrete variants`() {
        val classes = allNodeFixtures().map { it::class.simpleName }.toSet()
        assertEquals(23, classes.size, "Expected 23 distinct node classes, got $classes")
    }

    @Test
    fun `identity equality not structural equality`() {
        val a = MinMaxNode(ReadSqlSourceNode(FakeSqlDataSet))
        val b = MinMaxNode(ReadSqlSourceNode(FakeSqlDataSet))
        // Distinct objects → unequal even with the same fields.
        assertFalse(a == b)
        // But functionally identical (no fields differ aside from parents).
        assertTrue(a.functionallyIdentical(b))
    }

    @Test
    fun `functionallyIdentical compares stateful fields`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val a = OrderByLimitNode(parentNode = source, orderBySpecs = emptyList(), limit = 10)
        val b = OrderByLimitNode(parentNode = source, orderBySpecs = emptyList(), limit = 20)
        assertFalse(a.functionallyIdentical(b))
        val c = OrderByLimitNode(parentNode = source, orderBySpecs = emptyList(), limit = 10)
        assertTrue(a.functionallyIdentical(c))
    }

    @Test
    fun `withNewParents preserves all non-parent fields`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val orig = ConstrainTimeRangeNode(
            parentNode = source,
            timeRangeConstraint = cc.monomer.metricflow.common.time.TimeRangeConstraint(
                startTime = java.time.LocalDateTime.of(2024, 1, 1, 0, 0),
                endTime = java.time.LocalDateTime.of(2024, 12, 31, 0, 0),
            ),
        )
        val other = ReadSqlSourceNode(FakeSqlDataSet)
        val swapped = orig.withNewParents(listOf(other))
        assertEquals(orig.timeRangeConstraint, swapped.timeRangeConstraint)
        assertEquals(other, swapped.parentNode)
    }

    @Test
    fun `withNewParents enforces arity`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val node = MinMaxNode(source)
        try {
            node.withNewParents(emptyList())
            kotlin.test.fail("expected check failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("exactly one parent"))
        }
    }
}

class DataflowPlanTest {

    @Test
    fun `plan exposes its sink node`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val plan = DataflowPlan(source)
        assertEquals(source, plan.sinkNode)
        assertEquals(source, plan.renderNode)
        assertEquals(1, plan.nodeCount)
    }

    @Test
    fun `node count walks parents`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val mm = MinMaxNode(source)
        val plan = mm.asPlan()
        assertEquals(2, plan.nodeCount)
    }

    @Test
    fun `source semantic models collect from input nodes`() {
        val source = ReadSqlSourceNode(FakeSqlDataSet)
        val plan = MinMaxNode(source).asPlan()
        assertEquals(setOf(SemanticModelReference("listings_source")), plan.sourceSemanticModels)
    }

    @Test
    fun `dag id is auto-generated when not supplied`() {
        val plan = DataflowPlan(ReadSqlSourceNode(FakeSqlDataSet))
        assertNotNull(plan.dagId)
        assertTrue(plan.dagId.idStr.startsWith("dfp_"))
    }

    @Test
    fun `subgraph plan uses subgraph prefix`() {
        val plan = MinMaxNode(ReadSqlSourceNode(FakeSqlDataSet)).asPlan()
        assertTrue(plan.dagId.idStr.startsWith("dfpsub_"))
    }
}

class DataflowPlanAnalyzerTest {

    @Test
    fun `find common branches identifies the shared parent of a fork`() {
        // Build: shared = ReadSql
        //        left  = MinMax(shared)
        //        right = MinMax(shared)
        //        sink  = CombineAggregated(left, right)
        val shared = ReadSqlSourceNode(FakeSqlDataSet)
        val left = MinMaxNode(shared)
        val right = MinMaxNode(shared)
        val sink = CombineAggregatedOutputsNode(parentNodes = listOf(left, right))
        val plan = sink.asPlan()
        val common = DataflowPlanAnalyzer.findCommonBranches(plan)
        // `shared` is reached twice from `sink`, so it shows up as a common branch root.
        assertTrue(shared in common, "expected shared node in common branches, got: $common")
    }

    @Test
    fun `linear chain has no common branches`() {
        val plan = MinMaxNode(MinMaxNode(ReadSqlSourceNode(FakeSqlDataSet))).asPlan()
        val common = DataflowPlanAnalyzer.findCommonBranches(plan)
        assertTrue(common.isEmpty())
    }
}
