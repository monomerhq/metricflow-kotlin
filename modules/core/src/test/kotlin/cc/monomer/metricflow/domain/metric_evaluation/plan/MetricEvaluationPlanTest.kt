package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.spec.MetricSpec
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricEvaluationPlanTest {

    private fun props(): MetricQueryPropertySet = MetricQueryPropertySet.create(
        groupByItemSpecs = emptyList(),
        predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
    )

    @BeforeEach
    fun resetIds() {
        SequentialIdGenerator.reset()
    }

    @Test
    fun `validate accepts well-formed plan`() {
        val bookingsSpec = MetricSpec.fromElementName("bookings")
        val listingsSpec = MetricSpec.fromElementName("listings")
        val bplSpec = MetricSpec.fromElementName("bookings_per_listing")

        val bookings = SimpleMetricsQueryNode.create(
            modelId = SemanticModelId.getInstance("bookings_source"),
            metricSpecs = listOf(bookingsSpec),
            queryProperties = props(),
        )
        val listings = SimpleMetricsQueryNode.create(
            modelId = SemanticModelId.getInstance("listings_source"),
            metricSpecs = listOf(listingsSpec),
            queryProperties = props(),
        )
        val derived = DerivedMetricsQueryNode.create(
            computedMetricSpecs = listOf(bplSpec),
            passthroughMetricSpecs = emptyList(),
            queryProperties = props(),
        )
        val topLevel = TopLevelQueryNode.create(
            passthroughMetricSpecs = listOf(bplSpec),
            queryProperties = props(),
        )

        val plan = MutableMetricEvaluationPlan.create()
        plan.addNode(bookings)
        plan.addNode(listings)
        plan.addNode(derived)
        plan.addNode(topLevel)
        plan.addEdge(
            MetricQueryDependencyEdge.create(
                targetNode = derived,
                targetNodeOutputSpec = bplSpec,
                sourceNode = bookings,
                sourceNodeOutputSpec = bookingsSpec,
            ),
        )
        plan.addEdge(
            MetricQueryDependencyEdge.create(
                targetNode = derived,
                targetNodeOutputSpec = bplSpec,
                sourceNode = listings,
                sourceNodeOutputSpec = listingsSpec,
            ),
        )
        plan.addEdge(
            MetricQueryDependencyEdge.create(
                targetNode = topLevel,
                targetNodeOutputSpec = bplSpec,
                sourceNode = derived,
                sourceNodeOutputSpec = bplSpec,
            ),
        )

        // No throw == pass.
        plan.validate()

        val dfsOrder = plan.nodesInDfsOrder().toList()
        // Top-level emitted first, then its source (derived), then derived's sources.
        assertEquals(topLevel, dfsOrder.first())
        assertTrue(bookings in dfsOrder)
        assertTrue(listings in dfsOrder)
    }

    @Test
    fun `validate fails when there is no top-level node`() {
        val plan = MutableMetricEvaluationPlan.create()
        plan.addNode(
            SimpleMetricsQueryNode.create(
                modelId = SemanticModelId.getInstance("bookings_source"),
                metricSpecs = listOf(MetricSpec.fromElementName("bookings")),
                queryProperties = props(),
            ),
        )
        assertThrows<MetricFlowInternalError> { plan.validate() }
    }

    @Test
    fun `validate fails when passthrough spec is missing on a source edge`() {
        val bookingsSpec = MetricSpec.fromElementName("bookings")
        val bookings = SimpleMetricsQueryNode.create(
            modelId = SemanticModelId.getInstance("bookings_source"),
            metricSpecs = listOf(bookingsSpec),
            queryProperties = props(),
        )
        val topLevel = TopLevelQueryNode.create(
            passthroughMetricSpecs = listOf(bookingsSpec),
            queryProperties = props(),
        )

        // Note: no edge connecting top-level to bookings → passthrough invariant broken.
        val plan = MutableMetricEvaluationPlan.create()
        plan.addNode(bookings)
        plan.addNode(topLevel)
        assertThrows<MetricFlowInternalError> { plan.validate() }
    }

    @Test
    fun `pruned filters specs`() {
        val a = MetricSpec.fromElementName("a")
        val b = MetricSpec.fromElementName("b")
        val node = SimpleMetricsQueryNode.create(
            modelId = SemanticModelId.getInstance("m"),
            metricSpecs = listOf(a, b),
            queryProperties = props(),
        )
        val pruned = node.pruned(setOf(a))
        assertEquals(listOf(a), pruned.metricSpecs)
    }
}
