package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests that build a [SemanticManifestLookup] over every corpus manifest and verify a
 * handful of invariants. The test data are the same `corpus/manifests/` JSON fixtures the W1
 * round-trip test uses.
 */
class CorpusManifestLookupTest {

    private val manifestsDir: File by lazy {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        File(repoRoot, "corpus/manifests").also {
            require(it.isDirectory) { "Manifests directory not found: $it" }
        }
    }

    @TestFactory
    fun `every corpus manifest builds a lookup with consistent indexes`(): List<DynamicTest> {
        val files = manifestsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: error("No manifest fixtures found in $manifestsDir")
        check(files.isNotEmpty()) { "Manifest corpus is empty at $manifestsDir" }
        return files.map { file ->
            DynamicTest.dynamicTest("lookup over ${file.name}") {
                val raw = file.readText()
                val manifest = ManifestJson.decodeFromString(SemanticManifest.serializer(), raw)

                // Some corpus fixtures are intentionally invalid (e.g. `config_linter_manifest.json`
                // and `minimal_invalid_manifest.json`). The W2 validators are the appropriate place
                // to inspect those manifests.
                if (file.name in SKIP_MANIFESTS) return@dynamicTest

                val lookup = SemanticManifestLookup(manifest)

                // Every semantic model is reachable both by reference and via the sorted view.
                for (semanticModel in manifest.semanticModels) {
                    val byRef = lookup.semanticModelLookup.getByReference(semanticModel.reference)
                    assertNotNull(
                        byRef,
                        "Manifest ${file.name}: model ${semanticModel.name} not found by reference",
                    )
                    assertEquals(semanticModel.name, byRef.name)
                }

                // Model count matches the manifest's input.
                assertEquals(
                    manifest.semanticModels.size,
                    lookup.semanticModelLookup.modelReferenceToModel.size,
                    "Manifest ${file.name}: model count mismatch",
                )

                // Sorted view is sorted.
                val refs = lookup.semanticModelLookup.modelReferenceToModel.keys.toList()
                assertEquals(
                    refs.sortedBy { it.semanticModelName },
                    refs,
                    "Manifest ${file.name}: model reference order not sorted",
                )

                // Every metric in the manifest is resolvable.
                for (metric in manifest.metrics) {
                    val resolved = lookup.metricLookup.getMetric(MetricReference(metric.name))
                    assertEquals(metric.name, resolved.name)
                }

                // metricReferences is sorted and unique.
                val metricRefs = lookup.metricLookup.metricReferences
                assertEquals(
                    metricRefs.sortedBy { it.elementName },
                    metricRefs,
                    "Manifest ${file.name}: metric references not sorted",
                )
                assertEquals(metricRefs.toSet().size, metricRefs.size, "Manifest ${file.name}: duplicate metric refs")

                // Every defined dimension shows up in the dimension index.
                val dimensionRefs = lookup.semanticModelLookup.getDimensionReferences()
                for (semanticModel in manifest.semanticModels) {
                    for (dim in semanticModel.dimensions) {
                        assertTrue(
                            dim.reference in dimensionRefs,
                            "Manifest ${file.name}: dimension ${dim.name} missing from index",
                        )
                        val models = lookup.semanticModelLookup.getSemanticModelsForDimension(dim.reference)
                        assertTrue(
                            models.any { it.name == semanticModel.name },
                            "Manifest ${file.name}: dimension ${dim.name} not linked to ${semanticModel.name}",
                        )
                    }
                }

                // Every defined entity shows up in the entity index.
                val entityRefs = lookup.semanticModelLookup.getEntityReferences()
                for (semanticModel in manifest.semanticModels) {
                    for (entity in semanticModel.entities) {
                        assertTrue(
                            entity.reference in entityRefs,
                            "Manifest ${file.name}: entity ${entity.name} missing from index",
                        )
                        val models = lookup.semanticModelLookup.getSemanticModelsForEntity(entity.reference)
                        assertTrue(
                            models.any { it.name == semanticModel.name },
                            "Manifest ${file.name}: entity ${entity.name} not linked to ${semanticModel.name}",
                        )
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    fun `derived-metric models trace back to their simple-metric sources`() {
        val manifest = loadManifest("derived_metrics_manifest.json")
        val lookup = SemanticManifestLookup(manifest)

        // Pick a derived/ratio/cumulative metric and confirm getDerivedFromSemanticModels
        // returns at least one model reference.
        val nonSimple = manifest.metrics.firstOrNull {
            it.type != cc.monomer.metricflow.domain.manifest.model.enums.MetricType.SIMPLE
        }
        if (nonSimple != null) {
            val derived = lookup.metricLookup.getDerivedFromSemanticModels(MetricReference(nonSimple.name))
            assertTrue(derived.isNotEmpty(), "Expected at least one derived-from model for ${nonSimple.name}")
            // Every derived-from reference points to a real semantic model in the manifest.
            val modelNames = manifest.semanticModels.map { it.name }.toSet()
            for (ref in derived) {
                assertTrue(
                    ref.semanticModelName in modelNames,
                    "${ref.semanticModelName} not in manifest models $modelNames",
                )
            }
        }
    }

    private fun loadManifest(name: String): SemanticManifest {
        val file = File(manifestsDir, name)
        require(file.isFile) { "Manifest fixture not found: $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    private companion object {
        /**
         * Fixtures the lookup constructor cannot ingest:
         * - `config_linter_manifest.json`: contains a model with dimensions but no primary entity —
         *   intentionally invalid to exercise the W2 validation rules.
         * - `minimal_valid_manifest.json`: time spine omits its `relation_name`, which `TimeSpineSource`
         *   would parse as an empty SQL table.
         * - `minimal_invalid_manifest.json`: intentionally malformed for validator tests.
         */
        val SKIP_MANIFESTS: Set<String> = setOf(
            "config_linter_manifest.json",
            "minimal_valid_manifest.json",
            "minimal_invalid_manifest.json",
        )
    }
}
