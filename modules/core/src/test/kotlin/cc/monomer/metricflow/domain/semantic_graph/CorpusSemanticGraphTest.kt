package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertTrue

/**
 * Builds the semantic graph for every corpus manifest and verifies coarse
 * structural invariants. Mirrors the [CorpusManifestLookup][cc.monomer.metricflow.domain.lookup]
 * fixture handling by skipping the intentionally-invalid manifests.
 */
class CorpusSemanticGraphTest {

    private val manifestsDir: File by lazy {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        File(repoRoot, "corpus/manifests").also {
            require(it.isDirectory) { "Manifests directory not found: $it" }
        }
    }

    @TestFactory
    fun `semantic graph is buildable for every corpus manifest`(): List<DynamicTest> {
        val files = manifestsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: error("No manifest fixtures found in $manifestsDir")
        check(files.isNotEmpty()) { "Manifest corpus is empty at $manifestsDir" }

        return files.map { file ->
            DynamicTest.dynamicTest("semantic graph for ${file.name}") {
                if (file.name in SKIP_MANIFESTS) return@dynamicTest
                val manifest = ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
                if (manifest.semanticModels.isEmpty()) return@dynamicTest

                val manifestObjectLookup = ManifestObjectLookup(manifest)
                val graph = SemanticGraphBuilder(manifestObjectLookup).build()

                assertTrue(graph.nodes.size > 0, "graph nodes for ${file.name}")
                assertTrue(graph.edges.size > 0, "graph edges for ${file.name}")

                // Every semantic model corresponds to a JoinedModel node.
                for (model in manifest.semanticModels) {
                    val modelId = SemanticModelId.getInstance(model.name)
                    val joined = JoinedModelNode.getInstance(modelId)
                    assertTrue(joined in graph.nodes, "JoinedModelNode($modelId) for ${file.name}")
                    val local = LocalModelNode.getInstance(modelId)
                    assertTrue(local in graph.nodes, "LocalModelNode($modelId) for ${file.name}")
                }

                // Every simple metric should have a SimpleMetricNode reachable in the graph.
                val simpleMetricNames = manifestObjectLookup.simpleMetricNameToInput.keys
                for (name in simpleMetricNames) {
                    val node = SimpleMetricNode.getInstance(name)
                    assertTrue(node in graph.nodes, "SimpleMetricNode($name) for ${file.name}")
                }

                // Model aggregation and time dimensions expose the shared time-entity graph.
                assertTrue(MetricTimeNode in graph.nodes, "MetricTimeNode for ${file.name}")
                assertTrue(TimeNode in graph.nodes, "TimeNode for ${file.name}")
            }
        }
    }

    private companion object {
        val SKIP_MANIFESTS: Set<String> = setOf(
            "config_linter_manifest.json",
            "minimal_valid_manifest.json",
            "minimal_invalid_manifest.json",
        )
    }
}
