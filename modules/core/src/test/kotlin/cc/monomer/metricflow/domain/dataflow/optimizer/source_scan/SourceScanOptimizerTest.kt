package cc.monomer.metricflow.domain.dataflow.optimizer.source_scan

import cc.monomer.metricflow.domain.dataflow.DataflowPlan
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.dataflow.support.SqlDataSet
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.groupSpecByType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private object SourceA : SqlDataSet {
    override val semanticModelReference = SemanticModelReference("bookings_source")
}

private fun computeMetricsBranch(
    source: ReadSqlSourceNode,
    metricSpec: MetricSpec,
    includeSpec: SimpleMetricInputSpec,
): ComputeMetricsNode {
    val selector = SelectorNode(
        parentNode = source,
        includeSpecs = groupSpecByType(includeSpec),
        replaceDescription = null,
        distinct = false,
    )
    val agg = AggregateSimpleMetricInputsNode(
        parentNode = selector,
        nullFillValueMapping = NullFillValueMapping.EMPTY,
    )
    return ComputeMetricsNode.create(
        parentNode = agg,
        computedMetricSpecs = listOf(metricSpec),
        passthroughMetricSpecs = emptyList(),
        outputGroupByMetricInstances = false,
        aggregatedToElements = emptySet(),
    )
}

class SourceScanOptimizerTest {

    @Test
    fun `combinable branches collapse into one`() {
        val source = ReadSqlSourceNode(SourceA)
        val branch1 = computeMetricsBranch(
            source,
            MetricSpec.fromElementName("bookings"),
            SimpleMetricInputSpec(elementName = "bookings", fillNullsWith = null),
        )
        val branch2 = computeMetricsBranch(
            source,
            MetricSpec.fromElementName("booking_value"),
            SimpleMetricInputSpec(elementName = "booking_value", fillNullsWith = null),
        )
        // Both branches share the same source/spec set (linkable: empty); only their metrics differ.
        val combine = CombineAggregatedOutputsNode(parentNodes = listOf(branch1, branch2))
        val sink = WriteToResultDataTableNode(combine)
        val plan = DataflowPlan(sink)

        val optimized = SourceScanOptimizer().optimize(plan)

        // After combination we should no longer need a CombineAggregatedOutputsNode at the top
        // of the parent branches — the single combined ComputeMetricsNode feeds the write node.
        val newSink = optimized.sinkNode
        assertTrue(newSink is WriteToResultDataTableNode)
        val parent = newSink.parentNode
        assertTrue(parent is ComputeMetricsNode, "Expected combined ComputeMetricsNode, got ${parent::class.simpleName}")
        val computedMetricNames = parent.computedMetricSpecs.map { it.elementName }.toSet()
        assertEquals(setOf("bookings", "booking_value"), computedMetricNames)
    }

    @Test
    fun `incompatible linkable specs keep the combine node`() {
        val source = ReadSqlSourceNode(SourceA)
        // Two branches differ in the SelectorNode include specs — combination should fail.
        val branch1 = computeMetricsBranch(
            source,
            MetricSpec.fromElementName("bookings"),
            SimpleMetricInputSpec(elementName = "bookings", fillNullsWith = null),
        )
        val branch2 = ComputeMetricsNode.create(
            parentNode = AggregateSimpleMetricInputsNode(
                parentNode = SelectorNode(
                    parentNode = source,
                    includeSpecs = InstanceSpecSet(
                        metricSpecs = emptyList(),
                        simpleMetricInputSpecs = listOf(
                            SimpleMetricInputSpec(elementName = "booking_value", fillNullsWith = null),
                        ),
                        // Different linkable specs — not combinable with branch1.
                        dimensionSpecs = listOf(
                            cc.monomer.metricflow.domain.spec.DimensionSpec(
                                elementName = "is_instant",
                                entityLinks = emptyList(),
                                alias = null,
                            ),
                        ),
                        entitySpecs = emptyList(),
                        timeDimensionSpecs = emptyList(),
                        groupByMetricSpecs = emptyList(),
                        metadataSpecs = emptyList(),
                    ),
                    replaceDescription = null,
                    distinct = false,
                ),
                nullFillValueMapping = NullFillValueMapping.EMPTY,
            ),
            computedMetricSpecs = listOf(MetricSpec.fromElementName("booking_value")),
            passthroughMetricSpecs = emptyList(),
            outputGroupByMetricInstances = false,
            aggregatedToElements = emptySet(),
        )
        val combine = CombineAggregatedOutputsNode(parentNodes = listOf(branch1, branch2))
        val sink = WriteToResultDataTableNode(combine)
        val plan = DataflowPlan(sink)

        val optimized = SourceScanOptimizer().optimize(plan)
        val newSink = optimized.sinkNode
        // The combine node must survive — branches are not combinable.
        assertTrue(newSink is WriteToResultDataTableNode)
        assertTrue(newSink.parentNode is CombineAggregatedOutputsNode)
    }

    @Test
    fun `plain branch (no combine) passes through unchanged`() {
        val source: DataflowPlanNode = ReadSqlSourceNode(SourceA)
        val sink = WriteToResultDataTableNode(source)
        val plan = DataflowPlan(sink)

        val optimized = SourceScanOptimizer().optimize(plan)
        // With no combinable structure, the optimizer leaves the tree alone (nodes shared by
        // reference at every level).
        assertSame(source, (optimized.sinkNode as WriteToResultDataTableNode).parentNode)
    }
}
