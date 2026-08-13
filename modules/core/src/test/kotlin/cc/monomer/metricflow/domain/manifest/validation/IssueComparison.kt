package cc.monomer.metricflow.domain.manifest.validation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Convert one [ValidationIssue] to the same `JsonElement` shape the Python oracle helper
 * (`oracle.serialize.issue_to_dict`) emits, so the parity test can compare apples to apples.
 *
 * Format (mirrors the `expected.json` files under each `corpus/validate_manifest__...` case):
 *
 * ```
 * {
 *   "level": "ERROR" | "WARNING" | "FUTURE_ERROR",
 *   "message": "...",
 *   "context": { ... } | null,
 *   "context_str": "...",
 *   "extra_detail": "..." | null,
 *   "readable": "..."
 * }
 * ```
 *
 * Test-side only (not production); lives in `src/test/kotlin` to avoid adding a serialization
 * surface to the module's main artifact.
 */
internal fun issueToJson(issue: ValidationIssue): JsonObject = buildJsonObject {
    put("level", issue.level.name)
    put("message", issue.message)
    val ctx = issue.context
    if (ctx == null) {
        put("context", JsonNull)
    } else {
        put("context", contextToJson(ctx))
    }
    put("context_str", ctx?.contextStr().orEmpty())
    put("extra_detail", issue.extraDetail?.let { JsonPrimitive(it) } ?: JsonNull)
    put("readable", issue.asReadableStr())
}

/** Serialise a sequence of issues into a sorted [JsonArray] for stable parity comparison. */
internal fun issuesToSortedJson(issues: List<ValidationIssue>): JsonArray {
    val mapped = issues.map { issueToJson(it) }
    val sorted = mapped.sortedWith(IssueComparator)
    return JsonArray(sorted)
}

/**
 * Stable issue ordering for comparison. Python preserves the rule-emit order; we sort to make
 * the comparison ignore rule ordering quirks in cases where two rules emit issues with the
 * same `(level, message, context_str)`. Keys: level → message → context_str → readable.
 */
internal val IssueComparator: Comparator<JsonObject> = Comparator { a, b ->
    fun str(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.contentOrNullSafe ?: ""
    val byLevel = str(a, "level").compareTo(str(b, "level"))
    if (byLevel != 0) return@Comparator byLevel
    val byMsg = str(a, "message").compareTo(str(b, "message"))
    if (byMsg != 0) return@Comparator byMsg
    val byCtx = str(a, "context_str").compareTo(str(b, "context_str"))
    if (byCtx != 0) return@Comparator byCtx
    str(a, "readable").compareTo(str(b, "readable"))
}

private val JsonPrimitive.contentOrNullSafe: String?
    get() = if (this == JsonNull) null else content

private fun contextToJson(ctx: ValidationContext): JsonObject = when (ctx) {
    is FileContext -> buildJsonObject {
        put("file_name", ctx.fileName?.let { JsonPrimitive(it) } ?: JsonNull)
        put("line_number", ctx.lineNumber?.let { JsonPrimitive(it) } ?: JsonNull)
    }
    is MetricContext -> buildJsonObject {
        put("file_context", fileContextJson(ctx.fileContext))
        putJsonObject("metric") {
            put("metric_name", ctx.metric.metricName)
        }
    }
    is SemanticModelContext -> buildJsonObject {
        put("file_context", fileContextJson(ctx.fileContext))
        putJsonObject("semantic_model") {
            put("semantic_model_name", ctx.semanticModel.semanticModelName)
        }
    }
    is SemanticModelElementContext -> buildJsonObject {
        put("file_context", fileContextJson(ctx.fileContext))
        putJsonObject("semantic_model_element") {
            put("semantic_model_name", ctx.semanticModelElement.semanticModelName)
            put("element_name", ctx.semanticModelElement.elementName)
        }
        put("element_type", ctx.elementType.value)
    }
    is SavedQueryContext -> buildJsonObject {
        put("file_context", fileContextJson(ctx.fileContext))
        put("element_type", ctx.elementType.value)
        put("element_value", ctx.elementValue)
    }
    is ValidationIssueContext -> buildJsonObject {
        put("file_context", fileContextJson(ctx.fileContext))
        put("object_type", ctx.objectType)
        put("object_name", ctx.objectName)
    }
}

private fun fileContextJson(fc: FileContext): JsonObject = buildJsonObject {
    put("file_name", fc.fileName?.let { JsonPrimitive(it) } ?: JsonNull)
    put("line_number", fc.lineNumber?.let { JsonPrimitive(it) } ?: JsonNull)
}
