package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import kotlin.test.assertEquals

/**
 * Differential test: for every `validate_manifest__...` corpus case, run the Kotlin
 * [SemanticManifestValidator] on the (transformed) input manifest and compare the produced
 * issue list against the Python oracle's recorded output at
 * `corpus/validate_manifest__.../expected.json`.
 *
 * Acceptance bar from PROGRESS.md is **strict 100%** post-normalisation. We sort both sides
 * by `(level, message, context_str)` so emit-order differences don't cause spurious mismatches.
 *
 * Skipped fixtures: the two `minimal_*` hand-written fixtures, which are skipped in the W2a
 * parity test for the same W1-layer reason (`relation_name` / `dsi_package_version` auto-fill
 * mismatches). Those messages cascade into the validator output, so we skip them here too —
 * the 17 Pydantic-emitted canonical fixtures exercise every active rule.
 */
class PythonParityTest {

    private val repoRoot: File by lazy {
        val p = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        File(p).also { require(it.isDirectory) { "repoRoot must be a directory: $it" } }
    }

    @TestFactory
    fun `every validate_manifest case matches python oracle`(): List<DynamicTest> {
        val corpusRoot = File(repoRoot, "corpus")
        if (!corpusRoot.isDirectory) {
            return listOf(
                DynamicTest.dynamicTest("parity skipped — missing corpus directory") {
                    assumeTrue(false, "Corpus directory not found at $corpusRoot")
                },
            )
        }

        val caseDirs = corpusRoot.listFiles { f -> f.isDirectory && f.name.startsWith("validate_manifest__") }
            ?.sortedBy { it.name }
            ?: error("No validate_manifest cases under $corpusRoot")

        return caseDirs.map { dir ->
            DynamicTest.dynamicTest("parity ${dir.name}") {
                if (dir.name in SKIPPED_CASES) {
                    assumeTrue(
                        false,
                        "Hand-written minimal fixture: same skip rationale as W2a transformation " +
                            "PythonParityTest (W1 model intentionally does not port " +
                            "NodeRelation.relation_name / dsi_package_version auto-fill).",
                    )
                }
                runParity(dir)
            }
        }
    }

    private fun runParity(caseDir: File) {
        val requestFile = File(caseDir, "request.json")
        val expectedFile = File(caseDir, "expected.json")
        require(requestFile.isFile) { "Missing request.json in ${caseDir.name}" }
        require(expectedFile.isFile) { "Missing expected.json in ${caseDir.name}" }

        // The request.json carries the manifest fields at top level (semantic_models, metrics, etc.)
        // plus an "args" object we ignore here. The same SemanticManifest serializer used by W1 / W2a
        // parses this shape because the keys line up with the model module's snake_case naming.
        val manifest = requestJson.decodeFromString(SemanticManifest.serializer(), requestFile.readText())
        val transformed = SemanticManifestTransformer.transform(manifest)
        val results = SemanticManifestValidator.withDefaultRules().validate(transformed)

        val kotlinIssuesSorted = issuesToSortedJson(results.allIssues)

        val expectedRoot: JsonObject = parityJson.parseToJsonElement(expectedFile.readText()).jsonObject
        val expectedIssues = expectedRoot["issues"] as? JsonArray ?: JsonArray(emptyList())
        val pythonIssuesSorted = JsonArray(
            expectedIssues.map { it.jsonObject }.sortedWith(IssueComparator),
        )

        // Compare counts first for clearer error messages.
        assertEquals(
            pythonIssuesSorted.size,
            kotlinIssuesSorted.size,
            "Issue count differs for ${caseDir.name}.\n" +
                "Python: ${pythonIssuesSorted.size}\n" +
                "Kotlin: ${kotlinIssuesSorted.size}\n" +
                "Kotlin issues: $kotlinIssuesSorted\n" +
                "Python issues: $pythonIssuesSorted",
        )
        // Compare element-by-element.
        for ((idx, expected) in pythonIssuesSorted.withIndex()) {
            val actual = kotlinIssuesSorted[idx]
            assertEquals(
                expected,
                actual,
                "Issue #$idx differs for ${caseDir.name}.\n" +
                    "Expected (Python): $expected\n" +
                    "Actual   (Kotlin): $actual",
            )
        }
    }

    private companion object {
        val parityJson: Json = Json { ignoreUnknownKeys = true }

        /**
         * The `request.json` for each `validate_manifest__...` case carries an `args: {}` block
         * after the four manifest sections, which the W1 model module's `ManifestJson` doesn't
         * recognise. We use a permissive `Json` here so the `args` block is silently dropped —
         * exactly mirroring how the Python oracle ignores the `args` field for this subcommand
         * (see `oracle/commands/validate_manifest.py`).
         */
        @OptIn(ExperimentalSerializationApi::class)
        val requestJson: Json = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = true
        }

        /**
         * Skipped corpus cases — same rationale as W2a transformation parity test. The
         * minimal fixtures don't carry `node_relation.relation_name` /
         * `project_configuration.dsi_package_version`, which the Python validator surfaces
         * indirectly via the validation context strings. The 17 canonical Pydantic-emitted
         * fixtures cover every rule path.
         */
        val SKIPPED_CASES: Set<String> = setOf(
            "validate_manifest__minimal_fixture",
            "validate_manifest__minimal_invalid_fixture",
        )
    }
}
