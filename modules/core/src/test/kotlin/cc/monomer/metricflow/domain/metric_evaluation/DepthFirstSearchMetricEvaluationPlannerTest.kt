package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.metric_evaluation.plan.CumulativeMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.DerivedMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.SimpleMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.TopLevelQueryNode
import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory
import cc.monomer.metricflow.domain.query.resolution.FilterSpecResolutionLookUp
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociation
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthFirstSearchMetricEvaluationPlannerTest {

    /** A trivial resolver that just returns the element name as the column. */
    private class StubResolver : ColumnAssociationResolver {
        override fun resolveSpec(spec: InstanceSpec): ColumnAssociation =
            ColumnAssociation.ofSingle(spec.elementName)

        override fun withOptions(dunderPrefixSimpleMetricInputs: Boolean): ColumnAssociationResolver = this
    }

    @BeforeEach
    fun resetIds() {
        SequentialIdGenerator.reset()
    }

    @Test
    fun `build plan for single simple metric produces one base and one top-level node`() {
        val manifest = MetricEvaluationFixtures.manifest()
        val manifestLookup = SemanticManifestLookup(manifest)
        val manifestObjectLookup = ManifestObjectLookup(manifest)
        val planner = DepthFirstSearchMetricEvaluationPlanner(
            manifestObjectLookup = manifestObjectLookup,
            metricLookup = manifestLookup.metricLookup,
            columnAssociationResolver = StubResolver(),
        )

        val bookingsSpec = MetricSpec.fromElementName("bookings")
        val plan: MetricEvaluationPlan = planner.buildPlan(
            metricSpecs = listOf(bookingsSpec),
            groupByItemSpecs = emptyList(),
            predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
            filterSpecFactory = WhereFilterSpecFactory(
                columnAssociationResolver = StubResolver(),
                specResolutionLookup = FilterSpecResolutionLookUp.EMPTY,
                customGrainNames = emptyList(),
            ),
        )

        plan.validate()

        val simpleNodes = plan.nodes.filterIsInstance<SimpleMetricsQueryNode>()
        val topLevelNodes = plan.nodes.filterIsInstance<TopLevelQueryNode>()
        assertEquals(1, simpleNodes.size)
        assertEquals(1, topLevelNodes.size)
        assertEquals(listOf(bookingsSpec), simpleNodes.first().metricSpecs)
        assertEquals(listOf(bookingsSpec), topLevelNodes.first().passthroughMetricSpecs)
    }

    @Test
    fun `build plan for derived metric expands inputs`() {
        val manifest = MetricEvaluationFixtures.manifest()
        val manifestLookup = SemanticManifestLookup(manifest)
        val manifestObjectLookup = ManifestObjectLookup(manifest)
        val planner = DepthFirstSearchMetricEvaluationPlanner(
            manifestObjectLookup = manifestObjectLookup,
            metricLookup = manifestLookup.metricLookup,
            columnAssociationResolver = StubResolver(),
        )

        val bplSpec = MetricSpec.fromElementName("bookings_per_listing")
        val plan: MetricEvaluationPlan = planner.buildPlan(
            metricSpecs = listOf(bplSpec),
            groupByItemSpecs = emptyList(),
            predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
            filterSpecFactory = WhereFilterSpecFactory(
                columnAssociationResolver = StubResolver(),
                specResolutionLookup = FilterSpecResolutionLookUp.EMPTY,
                customGrainNames = emptyList(),
            ),
        )

        plan.validate()

        val simpleNodes = plan.nodes.filterIsInstance<SimpleMetricsQueryNode>()
        val derivedNodes = plan.nodes.filterIsInstance<DerivedMetricsQueryNode>()
        val topLevelNodes = plan.nodes.filterIsInstance<TopLevelQueryNode>()
        val cumulativeNodes = plan.nodes.filterIsInstance<CumulativeMetricQueryNode>()

        assertEquals(2, simpleNodes.size, "Expected one simple node for bookings and one for listings")
        assertEquals(1, derivedNodes.size, "Expected one derived node for bookings_per_listing")
        assertEquals(1, topLevelNodes.size)
        assertEquals(0, cumulativeNodes.size)

        val simpleMetricNames = simpleNodes.flatMap { it.metricSpecs }.map { it.elementName }.toSet()
        assertEquals(setOf("bookings", "listings"), simpleMetricNames)

        val derivedEdges = plan.sourceEdges(derivedNodes.first())
        assertEquals(2, derivedEdges.size)
        val sourceMetricNames = derivedEdges.map { it.sourceNodeOutputSpec.elementName }.toSet()
        assertEquals(setOf("bookings", "listings"), sourceMetricNames)
        assertTrue(derivedEdges.all { it.targetNodeOutputSpec == bplSpec })
    }
}
