package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.query.input.ResolverInputForApplyGroupBy
import cc.monomer.metricflow.domain.query.input.ResolverInputForGroupByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForLimit
import cc.monomer.metricflow.domain.query.input.ResolverInputForMetric
import cc.monomer.metricflow.domain.query.input.ResolverInputForMinMaxOnly
import cc.monomer.metricflow.domain.query.input.ResolverInputForQuery
import cc.monomer.metricflow.domain.query.input.ResolverInputForQueryLevelWhereFilterIntersection
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidLimitIssue
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidMetricIssue
import cc.monomer.metricflow.domain.query.issue.parsing.NoMetricOrGroupByIssue
import cc.monomer.metricflow.domain.query.naming.DunderNamingScheme
import cc.monomer.metricflow.domain.query.naming.MetricNamingScheme
import cc.monomer.metricflow.domain.query.naming.QueryItemLocation
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Functional tests for [MetricFlowQueryResolver].
 *
 * These tests cover the happy path (one metric + one group-by) and the
 * surface-level failure cases (unknown metric, negative limit, empty query).
 * Order-by and where-filter scenarios are covered by the per-feature tests
 * in :integration:diff-runner; here we focus on the resolver wiring.
 */
class MetricFlowQueryResolverTest {

    private fun loadManifest(name: String): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot") ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/$name")
        require(file.isFile) { "$name fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    private fun newResolver(manifestName: String = "simple_manifest.json"): MetricFlowQueryResolver {
        val manifest = loadManifest(manifestName)
        val manifestLookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(manifestLookup)
        return MetricFlowQueryResolver(manifestLookup = manifestLookup, graphLookup = graphLookup)
    }

    private fun metricInput(name: String, manifestName: String = "simple_manifest.json"): ResolverInputForMetric {
        val manifest = loadManifest(manifestName)
        val manifestLookup = SemanticManifestLookup(manifest)
        val scheme = MetricNamingScheme()
        return ResolverInputForMetric(
            inputObj = name,
            namingScheme = scheme,
            specPattern = scheme.specPattern(name, manifestLookup, QueryItemLocation.NON_ORDER_BY),
            alias = null,
        )
    }

    private fun groupByInput(name: String, manifestName: String = "simple_manifest.json"): ResolverInputForGroupByItem {
        val manifest = loadManifest(manifestName)
        val manifestLookup = SemanticManifestLookup(manifest)
        val scheme = DunderNamingScheme()
        return ResolverInputForGroupByItem(
            inputObj = name,
            inputObjNamingScheme = scheme,
            specPattern = scheme.specPattern(name, manifestLookup, QueryItemLocation.NON_ORDER_BY),
            alias = null,
        )
    }

    private fun emptyQueryInputFor(
        metrics: List<ResolverInputForMetric>,
        groupBys: List<ResolverInputForGroupByItem>,
        limit: Int? = null,
    ): ResolverInputForQuery = ResolverInputForQuery(
        metricInputs = metrics,
        groupByItemInputs = groupBys,
        filterInput = ResolverInputForQueryLevelWhereFilterIntersection(
            whereFilterIntersection = WhereFilterIntersection(whereFilters = emptyList()),
        ),
        orderByItemInputs = emptyList(),
        limitInput = ResolverInputForLimit(limit),
        minMaxOnly = ResolverInputForMinMaxOnly(false),
        applyGroupBy = ResolverInputForApplyGroupBy(true),
    )

    @Test
    fun `bookings with metric_time__day resolves to a non-null spec`() {
        val resolver = newResolver()
        val input = emptyQueryInputFor(
            metrics = listOf(metricInput("bookings")),
            groupBys = listOf(groupByInput("metric_time__day")),
        )
        val result = resolver.resolveQuery(input)
        assertFalse(result.hasErrors, "Expected no errors, got: ${result.inputToIssueSet}")
        val spec = result.checkedQuerySpec
        assertEquals(1, spec.metricSpecs.size)
        assertEquals("bookings", spec.metricSpecs[0].elementName)
        assertEquals(1, spec.timeDimensionSpecs.size)
        assertEquals("metric_time", spec.timeDimensionSpecs[0].elementName)
    }

    @Test
    fun `unknown metric surfaces an InvalidMetricIssue`() {
        // Build the resolver-input by hand because the parser would have
        // rejected the name in convertInputsToResolverInputForQuery; we want
        // to exercise the resolver path specifically.
        val resolver = newResolver()
        // Use a non-existent metric name — the spec pattern will be valid but
        // no metric reference will match.
        val unknownInput = metricInput("nonexistent_metric")
        val input = emptyQueryInputFor(
            metrics = listOf(unknownInput),
            groupBys = emptyList(),
        )
        val result = resolver.resolveQuery(input)
        assertTrue(result.hasErrors, "Expected errors but got: ${result.querySpec}")
        val allIssues = result.inputToIssueSet.items.flatMap { it.issueSet.issues }
        assertTrue(
            allIssues.any { it is InvalidMetricIssue },
            "Expected an InvalidMetricIssue, got: $allIssues",
        )
    }

    @Test
    fun `empty query without metrics or group-by raises NoMetricOrGroupByIssue`() {
        val resolver = newResolver()
        val input = emptyQueryInputFor(metrics = emptyList(), groupBys = emptyList())
        val result = resolver.resolveQuery(input)
        assertTrue(result.hasErrors)
        val allIssues = result.inputToIssueSet.items.flatMap { it.issueSet.issues }
        assertTrue(allIssues.any { it is NoMetricOrGroupByIssue })
    }

    @Test
    fun `negative limit raises InvalidLimitIssue`() {
        val resolver = newResolver()
        val input = emptyQueryInputFor(
            metrics = listOf(metricInput("bookings")),
            groupBys = emptyList(),
            limit = -5,
        )
        val result = resolver.resolveQuery(input)
        assertTrue(result.hasErrors)
        val allIssues = result.inputToIssueSet.items.flatMap { it.issueSet.issues }
        assertTrue(allIssues.any { it is InvalidLimitIssue })
    }

    @Test
    fun `queriedSemanticModels is populated for a simple metric`() {
        val resolver = newResolver()
        val input = emptyQueryInputFor(
            metrics = listOf(metricInput("bookings")),
            groupBys = listOf(groupByInput("metric_time__day")),
        )
        val result = resolver.resolveQuery(input)
        assertFalse(result.hasErrors)
        // The 'bookings' simple metric is defined on a semantic model; the
        // semantic-model accounting should surface it.
        assertNotNull(result.queriedSemanticModels)
        assertTrue(
            result.queriedSemanticModels.isNotEmpty(),
            "Expected at least one queried semantic model, got: ${result.queriedSemanticModels}",
        )
    }

    @Test
    fun `parser end-to-end with graphLookup returns a non-null query spec`() {
        val manifest = loadManifest("simple_manifest.json")
        val manifestLookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(manifestLookup)
        val parser = MetricFlowQueryParser(
            semanticManifestLookup = manifestLookup,
            graphLookup = graphLookup,
        )
        val resolution = parser.parseAndValidateQuery(
            metricNames = listOf("bookings"),
            metrics = emptyList(),
            groupByNames = listOf("metric_time__day"),
            groupBy = emptyList(),
            limit = null,
            timeConstraintStart = null,
            timeConstraintEnd = null,
            whereConstraints = emptyList(),
            whereConstraintStrs = emptyList(),
            orderByNames = emptyList(),
            orderBy = emptyList(),
            minMaxOnly = false,
            applyGroupBy = true,
        )
        assertFalse(resolution.hasErrors)
        val spec = resolution.checkedQuerySpec
        assertEquals(1, spec.metricSpecs.size)
        assertEquals("bookings", spec.metricSpecs[0].elementName)
    }
}
