package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.common.errors.MetricDefinitionDependencyError
import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.metric_evaluation.plan.CumulativeMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.DerivedMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.metric_evaluation.plan.SimpleMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.TopLevelQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.passthrough.PassThroughMetricEvaluationPlanner
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
import kotlin.test.assertFailsWith
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

    @Test
    fun `metric definition depth accepts one hundred metric levels`() {
        val manifest = MetricEvaluationFixtures.manifestWithMetricLevels(
            MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH,
        )
        val planner = plannerFor(manifest)
        val rootMetricSpec = MetricSpec.fromElementName(
            "metric_level_${MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH}",
        )

        val plan = planner.buildPlan(
            metricSpecs = listOf(rootMetricSpec),
            groupByItemSpecs = emptyList(),
            predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
            filterSpecFactory = filterSpecFactory(),
        )

        assertEquals(MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH + 1, plan.nodes.size)
    }

    @Test
    fun `metric definition depth rejects level one hundred and one`() {
        val rejectedLevel = MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH + 1
        val manifest = MetricEvaluationFixtures.manifestWithMetricLevels(rejectedLevel)
        val planner = plannerFor(manifest)

        val error = assertFailsWith<MetricDefinitionDependencyError> {
            planner.buildPlan(
                metricSpecs = listOf(MetricSpec.fromElementName("metric_level_$rejectedLevel")),
                groupByItemSpecs = emptyList(),
                predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
                filterSpecFactory = filterSpecFactory(),
            )
        }

        assertTrue(error.message.orEmpty().contains("maximum of 100 levels"))
    }

    @Test
    fun `metric dependency cycle fails without requeueing forever`() {
        val planner = plannerFor(MetricEvaluationFixtures.manifestWithMetricCycle())

        val error = assertFailsWith<MetricDefinitionDependencyError> {
            planner.buildPlan(
                metricSpecs = listOf(MetricSpec.fromElementName("metric_a")),
                groupByItemSpecs = emptyList(),
                predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
                filterSpecFactory = filterSpecFactory(),
            )
        }

        assertTrue(error.message.orEmpty().contains("metric_a -> metric_b -> metric_a"))
    }

    @Test
    fun `pass-through planner accepts one hundred metric levels`() {
        val maximumLevels = MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH
        val planner = passThroughPlannerFor(MetricEvaluationFixtures.manifestWithMetricLevels(maximumLevels))

        val plan = planner.buildPlan(
            metricSpecs = listOf(MetricSpec.fromElementName("metric_level_$maximumLevels")),
            groupByItemSpecs = emptyList(),
            predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
            filterSpecFactory = filterSpecFactory(),
        )

        assertTrue(plan.nodes.isNotEmpty())
    }

    @Test
    fun `pass-through planner rejects level one hundred and one`() {
        val rejectedLevel = MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH + 1
        val planner = passThroughPlannerFor(MetricEvaluationFixtures.manifestWithMetricLevels(rejectedLevel))

        val error = assertFailsWith<MetricDefinitionDependencyError> {
            planner.buildPlan(
                metricSpecs = listOf(MetricSpec.fromElementName("metric_level_$rejectedLevel")),
                groupByItemSpecs = emptyList(),
                predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
                filterSpecFactory = filterSpecFactory(),
            )
        }

        assertTrue(error.message.orEmpty().contains("maximum of 100 levels"))
    }

    @Test
    fun `pass-through planner rejects a metric dependency cycle`() {
        val planner = passThroughPlannerFor(MetricEvaluationFixtures.manifestWithMetricCycle())

        val error = assertFailsWith<MetricDefinitionDependencyError> {
            planner.buildPlan(
                metricSpecs = listOf(MetricSpec.fromElementName("metric_a")),
                groupByItemSpecs = emptyList(),
                predicatePushdownState = PredicatePushdownState.withPushdownDisabled(),
                filterSpecFactory = filterSpecFactory(),
            )
        }

        assertTrue(error.message.orEmpty().contains("metric_a -> metric_b -> metric_a"))
    }

    private fun plannerFor(
        manifest: cc.monomer.metricflow.domain.manifest.model.SemanticManifest,
    ): DepthFirstSearchMetricEvaluationPlanner {
        val manifestLookup = SemanticManifestLookup(manifest)
        return DepthFirstSearchMetricEvaluationPlanner(
            manifestObjectLookup = ManifestObjectLookup(manifest),
            metricLookup = manifestLookup.metricLookup,
            columnAssociationResolver = StubResolver(),
        )
    }

    private fun passThroughPlannerFor(
        manifest: cc.monomer.metricflow.domain.manifest.model.SemanticManifest,
    ): PassThroughMetricEvaluationPlanner {
        val manifestLookup = SemanticManifestLookup(manifest)
        return PassThroughMetricEvaluationPlanner(
            manifestObjectLookup = ManifestObjectLookup(manifest),
            metricLookup = manifestLookup.metricLookup,
            columnAssociationResolver = StubResolver(),
        )
    }

    private fun filterSpecFactory(): WhereFilterSpecFactory = WhereFilterSpecFactory(
        columnAssociationResolver = StubResolver(),
        specResolutionLookup = FilterSpecResolutionLookUp.EMPTY,
        customGrainNames = emptyList(),
    )
}
