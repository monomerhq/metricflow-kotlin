package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.query.parameter.DimensionOrEntityParameter
import cc.monomer.metricflow.domain.query.parameter.GroupByQueryParameter
import cc.monomer.metricflow.domain.query.parameter.MetricParameter
import cc.monomer.metricflow.domain.query.parameter.MetricQueryParameter
import cc.monomer.metricflow.domain.query.parameter.OrderByQueryParameter
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetricFlowQueryParserTest {

    private fun loadManifest(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot") ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/simple_manifest.json")
        require(file.isFile) { "simple_manifest.json fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `convertInputsToResolverInputForQuery wires string metric and group-by`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val parser = MetricFlowQueryParser(lookup)
        val resolverInput = parser.convertInputsToResolverInputForQuery(
            metricNames = listOf("bookings"),
            metrics = emptyList(),
            groupByNames = listOf("listing__country"),
            groupBy = emptyList(),
            whereConstraints = emptyList(),
            orderBy = emptyList<OrderByQueryParameter>(),
            orderByNames = emptyList(),
            limit = null,
            minMaxOnly = false,
            applyGroupBy = true,
        )
        assertEquals(1, resolverInput.metricInputs.size)
        assertEquals("bookings", resolverInput.metricInputs[0].inputObj.toString())
        assertEquals(1, resolverInput.groupByItemInputs.size)
    }

    @Test
    fun `convertInputsToResolverInputForQuery accepts MetricParameter and DimensionOrEntityParameter`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val parser = MetricFlowQueryParser(lookup)
        val resolverInput = parser.convertInputsToResolverInputForQuery(
            metricNames = emptyList(),
            metrics = listOf<MetricQueryParameter>(MetricParameter("bookings", null)),
            groupByNames = emptyList(),
            groupBy = listOf<GroupByQueryParameter>(DimensionOrEntityParameter("listing__country", null)),
            whereConstraints = emptyList(),
            orderBy = emptyList(),
            orderByNames = emptyList(),
            limit = 100,
            minMaxOnly = false,
            applyGroupBy = true,
        )
        assertEquals(1, resolverInput.metricInputs.size)
        assertEquals(1, resolverInput.groupByItemInputs.size)
        assertEquals(100, resolverInput.limitInput.limit)
    }

    @Test
    fun `unknown metric name throws`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val parser = MetricFlowQueryParser(lookup)
        // A metric name with '(' fails the MetricNamingScheme regex AND the
        // (currently stubbed) ObjectBuilderNamingScheme.
        assertFailsWith<IllegalArgumentException> {
            parser.convertInputsToResolverInputForQuery(
                metricNames = listOf("Metric('bookings')"),
                metrics = emptyList(),
                groupByNames = emptyList(),
                groupBy = emptyList(),
                whereConstraints = emptyList(),
                orderBy = emptyList(),
                orderByNames = emptyList(),
                limit = null,
                minMaxOnly = false,
                applyGroupBy = true,
            )
        }
    }

    @Test
    fun `buildResolutionDag wraps a single metric in a query node`() {
        val lookup = SemanticManifestLookup(loadManifest())
        val parser = MetricFlowQueryParser(lookup)
        val resolverInput = parser.convertInputsToResolverInputForQuery(
            metricNames = listOf("bookings"),
            metrics = emptyList(),
            groupByNames = emptyList(),
            groupBy = emptyList(),
            whereConstraints = emptyList(),
            orderBy = emptyList(),
            orderByNames = emptyList(),
            limit = null,
            minMaxOnly = false,
            applyGroupBy = true,
        )
        val dag = parser.buildResolutionDag(resolverInput)
        val query = dag.sinkNode as cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.QueryGroupByItemResolutionNode
        assertTrue(query.metricsInQuery.any { it.elementName == "bookings" })
    }

    @Test
    fun `parseAndValidateQuery requires a graphLookup`() {
        val lookup = SemanticManifestLookup(loadManifest())
        // Without the graphLookup variant, the parser deliberately defers to W14a's
        // dispatch — the resolver requires the W7c graph for group-by lookup.
        val parser = MetricFlowQueryParser(lookup)
        assertFailsWith<NotImplementedError> {
            parser.parseAndValidateQuery(
                metricNames = listOf("bookings"),
                metrics = emptyList(),
                groupByNames = emptyList(),
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
        }
    }
}
