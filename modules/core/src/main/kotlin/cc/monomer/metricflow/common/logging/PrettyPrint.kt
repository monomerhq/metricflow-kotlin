package cc.monomer.metricflow.common.logging

/**
 * Producers can implement this interface to take over their own formatting
 * inside the metricflow pretty-printer.
 *
 * Port of `metricflow_semantics.toolkit.mf_logging.pretty_formattable.MetricFlowPrettyFormattable`.
 * The Python interface returns `Optional[str]` — `None` signals "use the
 * default formatter"; we model that with a `String?` here.
 */
fun interface MetricFlowPrettyFormattable {
    fun prettyFormat(): String?
}

/**
 * Returns a pretty-printed string of any object.
 *
 * Port of `metricflow_semantics.toolkit.mf_logging.pretty_print.mf_pformat`.
 * The Python implementation has many tuning knobs and includes a custom
 * indentation pass; this Kotlin port keeps the common shape:
 *
 * - Primitives → their `toString()`.
 * - `String` → wrapped in single quotes.
 * - `null` → `"null"`.
 * - `List` / `Set` → `[a, b, c]`.
 * - `Map` → `{k1: v1, k2: v2}`.
 * - Objects implementing [MetricFlowPrettyFormattable] → their `prettyFormat()`,
 *   falling through to the default formatter when it returns `null`.
 */
fun mfPformat(value: Any?): String = when (value) {
    null -> "null"
    is String -> "'$value'"
    is MetricFlowPrettyFormattable -> value.prettyFormat() ?: defaultFormat(value)
    is Map<*, *> -> value.entries.joinToString(", ", prefix = "{", postfix = "}") { (k, v) ->
        "${mfPformat(k)}: ${mfPformat(v)}"
    }
    is List<*> -> value.joinToString(", ", prefix = "[", postfix = "]") { mfPformat(it) }
    is Set<*> -> value.joinToString(", ", prefix = "{", postfix = "}") { mfPformat(it) }
    is Array<*> -> value.joinToString(", ", prefix = "[", postfix = "]") { mfPformat(it) }
    else -> defaultFormat(value)
}

/**
 * Format [title] followed by the key/value pairs of [data], one per indented line.
 *
 * Port of `metricflow_semantics.toolkit.mf_logging.pretty_print.mf_pformat_dict`.
 */
fun mfPformatDict(title: String, data: Map<String, Any?>): String {
    if (data.isEmpty()) return title
    val sb = StringBuilder(title)
    for ((k, v) in data) {
        sb.append("\n    ").append(k).append(": ").append(mfPformat(v))
    }
    return sb.toString()
}

private fun defaultFormat(value: Any): String = value.toString()
