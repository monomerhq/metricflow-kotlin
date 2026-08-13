package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Sanity tests that build [InstanceSpec] variants from real corpus manifests
 * and verify the basics — dunder names, reference round-trip, visitor
 * dispatch, and resolver output.
 *
 * This is *not* a full Python-vs-Kotlin equivalence test (the SQL pipeline
 * lands in W9); it just confirms the spec layer can absorb arbitrary corpus
 * manifests without blowing up on element-name conventions.
 */
class CorpusSpecConstructionTest {

    private val manifestsDir: File by lazy {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        File(repoRoot, "corpus/manifests").also {
            require(it.isDirectory) { "Manifests directory not found: $it" }
        }
    }

    private val resolver = DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs = true)

    @TestFactory
    fun `every corpus manifest produces resolvable spec names`(): List<DynamicTest> {
        val files = manifestsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: error("No manifest fixtures found in $manifestsDir")
        check(files.isNotEmpty()) { "Manifest corpus is empty at $manifestsDir" }
        return files.map { file ->
            DynamicTest.dynamicTest("specs from ${file.name}") {
                val raw = file.readText()
                val manifest = ManifestJson.decodeFromString(SemanticManifest.serializer(), raw)

                // Build a representative spec for every dimension, entity, and metric and
                // make sure the resolver runs cleanly. The construction itself catches name
                // edge cases (e.g. double underscores already in names — see the
                // NonAdditiveDimensionSpec invariant).
                for (model in manifest.semanticModels) {
                    for (dimension in model.dimensions) {
                        val spec = DimensionSpec.fromElementName(dimension.name)
                        assertNotNull(resolver.resolveSpec(spec))
                    }
                    for (entity in model.entities) {
                        val spec = EntitySpec.fromElementName(entity.name)
                        assertNotNull(resolver.resolveSpec(spec))
                    }
                }
                for (metric in manifest.metrics) {
                    val spec = MetricSpec.fromElementName(metric.name)
                    assertEquals(metric.name, resolver.resolveSpec(spec).columnName)
                }

                // metric_time at DAY grain should always resolve to "metric_time__day".
                val mt = TimeDimensionSpec(
                    elementName = cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME,
                    entityLinks = emptyList(),
                    timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
                    datePart = null,
                    aggregationState = null,
                    windowFunctions = emptyList(),
                    alias = null,
                )
                assertEquals("metric_time__day", resolver.resolveSpec(mt).columnName)
                assertTrue(mt.isMetricTime)
            }
        }
    }
}
