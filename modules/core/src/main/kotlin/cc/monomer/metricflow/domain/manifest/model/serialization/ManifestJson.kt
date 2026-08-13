package cc.monomer.metricflow.domain.manifest.model.serialization

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/**
 * The canonical [Json] used to parse and re-serialise semantic manifests.
 *
 * Configuration choices:
 * - `namingStrategy = SnakeCase`: Kotlin's `lowerCamelCase` property names map to the manifest's
 *   `snake_case` JSON keys. Avoids hundreds of `@SerialName` declarations.
 * - `ignoreUnknownKeys = false`: strict — if the JSON has a key our model doesn't, throw.
 *   Catches schema drift between metricflow and our port.
 * - `prettyPrint = true` and `prettyPrintIndent = "  "`: re-serialised output is human-readable
 *   and structurally similar to Python's `json.dumps(..., indent=2)`. Pretty-print does not affect
 *   the result of `JsonElement` equality, so the round-trip test still works.
 * - `explicitNulls = true` (default): preserves `"x": null` fields exactly as Python emits them.
 *
 * Default values on Kotlin data classes ARE called when a JSON key is absent. Only `null` defaults
 * are permitted (CLAUDE.md "Explicit Code" — exception (a) for primitive scalars / nullable
 * fields where Pydantic was unconditional).
 */
@OptIn(ExperimentalSerializationApi::class)
val ManifestJson: Json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    ignoreUnknownKeys = false
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = true
}
