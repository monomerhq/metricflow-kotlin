package cc.monomer.metricflow.domain.manifest.transformation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Idempotence smoke test: running [SemanticManifestTransformer.transform] on an already-
 * transformed manifest must produce the same manifest (`transform(transform(m)) == transform(m)`).
 *
 * This is weaker than the Python-parity test but doesn't require the Python venv, so it serves
 * as a CI-friendly sanity check that none of the rules introduce non-deterministic mutations or
 * accumulate state across runs.
 */
class TransformerSmokeTest {

    private val manifestsDir: File by lazy {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        File(repoRoot, "corpus/manifests").also {
            require(it.isDirectory) { "Manifests directory not found: $it" }
        }
    }

    @Test
    fun `transformer can be invoked with default rule set`() {
        // Sanity: the default singleton compiles and exposes 14 rules across two phases.
        val rules = DefaultTransformRuleSet
        assertEquals(1, rules.primaryRules.size, "Expected 1 primary rule (LowerCaseNamesRule)")
        assertEquals(13, rules.secondaryRules.size, "Expected 13 secondary rules")
    }

    @TestFactory
    fun `transform is idempotent on every corpus manifest`(): List<DynamicTest> {
        val files = manifestsDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: error("No manifest fixtures found in $manifestsDir")
        check(files.isNotEmpty()) { "Manifest corpus is empty at $manifestsDir" }
        return files.map { file ->
            DynamicTest.dynamicTest("idempotent ${file.name}") {
                val parsed = ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
                val once = SemanticManifestTransformer.transform(parsed)
                val twice = SemanticManifestTransformer.transform(once)
                assertEquals(once, twice, "Second transform should be a no-op (${file.name})")
            }
        }
    }
}
