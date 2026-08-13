package cc.monomer.metricflow.integration.diff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Iterates `corpus/<case>/meta.json` and yields one [CorpusCase] per directory.
 *
 * Each case carries:
 * - `meta.json` — subcommand, manifest_id, args (typed via [CorpusCase.args]).
 * - `request.json` — top-level manifest fields (`semantic_models`, `metrics`,
 *   `project_configuration`, `saved_queries`) plus a duplicate `args` block we
 *   ignore here. This is the exact payload the Python oracle CLI consumed.
 * - `expected.json` — the Python oracle's recorded output.
 *
 * Skips `corpus/manifests/` (those are manifest fixtures shared between cases,
 * not test cases themselves) and any directory that lacks a `meta.json`.
 */
object CorpusLoader {

    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun load(corpusRoot: File): List<CorpusCase> {
        require(corpusRoot.isDirectory) { "Not a directory: $corpusRoot" }
        return corpusRoot.listFiles().orEmpty()
            .asSequence()
            .filter { it.isDirectory && it.name != "manifests" }
            .mapNotNull { dir ->
                val metaFile = File(dir, "meta.json")
                if (!metaFile.isFile) null else parse(dir, metaFile)
            }
            .sortedBy { it.caseId }
            .toList()
    }

    private fun parse(caseDir: File, metaFile: File): CorpusCase {
        val obj = json.parseToJsonElement(metaFile.readText()).jsonObject
        val subcommand = obj["subcommand"]?.jsonPrimitive?.content
            ?: error("meta.json for ${caseDir.name} is missing 'subcommand'")
        val manifestId = obj["manifest_id"]?.jsonPrimitive?.content.orEmpty()
        val args: JsonObject = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
        val dialects = obj["dialect_set"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            .orEmpty()
        return CorpusCase(
            caseId = caseDir.name,
            subcommand = subcommand,
            manifestId = manifestId,
            args = args,
            dialectSet = dialects,
            caseDir = caseDir,
        )
    }
}
