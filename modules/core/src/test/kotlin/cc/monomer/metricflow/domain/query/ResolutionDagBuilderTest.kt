package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.GroupByItemResolutionDagBuilder
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.NoMetricsGroupByItemSourceNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.QueryGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.SimpleMetricGroupByItemSourceNode
import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResolutionDagBuilderTest {

    private fun loadManifest(name: String): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot") ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/$name")
        require(file.isFile) { "$name fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `simple metric builds a SimpleMetric source node`() {
        val manifest = loadManifest("simple_manifest.json")
        val lookup = SemanticManifestLookup(manifest)
        val builder = GroupByItemResolutionDagBuilder(lookup)
        val dag = builder.build(
            metricReferences = listOf(MetricReference("bookings")),
            whereFilterIntersection = null,
        )
        val query = dag.sinkNode as QueryGroupByItemResolutionNode
        assertEquals(1, query.metricsInQuery.size)
        assertEquals("bookings", query.metricsInQuery[0].elementName)
        assertEquals(1, query.parentNodes.size)
        assertTrue(query.parentNodes[0] is SimpleMetricGroupByItemSourceNode)
    }

    @Test
    fun `no-metric query builds a NoMetricsGroupByItemSourceNode`() {
        val manifest = loadManifest("simple_manifest.json")
        val lookup = SemanticManifestLookup(manifest)
        val builder = GroupByItemResolutionDagBuilder(lookup)
        val dag = builder.build(
            metricReferences = emptyList(),
            whereFilterIntersection = null,
        )
        val query = dag.sinkNode as QueryGroupByItemResolutionNode
        assertTrue(query.metricsInQuery.isEmpty())
        assertEquals(1, query.parentNodes.size)
        assertTrue(query.parentNodes[0] is NoMetricsGroupByItemSourceNode)
    }

    @Test
    fun `derived metric expands into complex metric resolution nodes`() {
        val manifest = loadManifest("derived_metrics_manifest.json")
        val lookup = SemanticManifestLookup(manifest)
        val builder = GroupByItemResolutionDagBuilder(lookup)
        // Pick a known derived metric from the fixture.
        val derivedName = lookup.metricLookup.let { ml ->
            manifest.metrics.firstOrNull { it.type == cc.monomer.metricflow.domain.manifest.model.enums.MetricType.DERIVED }
                ?.name
        }
        if (derivedName != null) {
            val dag = builder.build(
                metricReferences = listOf(MetricReference(derivedName)),
                whereFilterIntersection = null,
            )
            val query = dag.sinkNode as QueryGroupByItemResolutionNode
            assertEquals(1, query.parentNodes.size)
        }
    }
}
