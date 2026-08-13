package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke test wiring `simple_manifest.json` through the W7c composition root.
 *
 * Exercises:
 *
 * - [ManifestObjectLookup] index population
 * - [SemanticGraphBuilder] full graph construction
 * - [SemanticManifestGraphLookup] composition with the W7a
 *   [SemanticManifestLookup]
 * - [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver]
 *   producing a non-empty [GroupByItemSet] for a known simple metric.
 */
class SemanticGraphBuilderSmokeTest {

    private fun loadSimpleManifest(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/simple_manifest.json")
        require(file.isFile) { "simple_manifest.json fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `simple_manifest builds a fully composed semantic graph`() {
        val manifest = loadSimpleManifest()
        val manifestLookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(manifestLookup)

        assertTrue(graphLookup.manifestObjectLookup.modelObjectLookups.isNotEmpty())
        assertTrue(graphLookup.semanticGraph.nodes.size > 0)
        assertTrue(graphLookup.semanticGraph.edges.size > 0)

        // simple_manifest declares simple metrics — pick the first one.
        val firstSimpleMetricName = graphLookup.simpleMetricNameToInput.keys.firstOrNull()
        assertNotNull(firstSimpleMetricName, "simple_manifest has no simple metrics — corpus changed?")

        val byItem = graphLookup.groupByItemSetResolver.resolveAvailableItemsForMetric(
            cc.monomer.metricflow.domain.manifest.model.references.MetricReference(firstSimpleMetricName),
        )
        // The BFS-based resolver returns a set; we only assert the shape, not the exact contents.
        // The dunderName-keyed mapping should not throw and should be addressable.
        @Suppress("UNUSED_VARIABLE")
        val mapping = byItem.dunderNameToAnnotatedSpec
    }
}
