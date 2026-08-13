package cc.monomer.metricflow.common.util

/** Two-space indent. Port of `MF_INDENT_2_SPACE`. */
const val MF_INDENT_2_SPACE: String = "  "

/** Joins the given strings with newline separators. Port of `mf_newline_join`. */
fun mfNewlineJoin(vararg lines: String): String = lines.joinToString("\n")

/**
 * Indents every line of [message] with [indentPrefix] repeated [indentLevel] times.
 *
 * Mirrors `metricflow_semantics.toolkit.string_helpers.mf_indent`.
 */
fun mfIndent(message: String, indentLevel: Int, indentPrefix: String): String {
    val prefix = indentPrefix.repeat(indentLevel)
    return message.lineSequence().joinToString("\n") { line ->
        if (line.isEmpty()) line else "$prefix$line"
    }
}

/** Default-argument overload — `indentLevel=1`, `indentPrefix=MF_INDENT_2_SPACE`. */
fun mfIndent(message: String): String = mfIndent(message, indentLevel = 1, indentPrefix = MF_INDENT_2_SPACE)

/**
 * Removes leading newlines, dedents, and removes trailing newlines.
 *
 * Mirrors Python's `mf_dedent`. The dedenting algorithm follows
 * `textwrap.dedent`: it strips the longest common whitespace prefix shared
 * by all non-blank lines.
 */
fun mfDedent(text: String): String {
    val withoutLeadingNewlines = text.trimStart('\n')
    val lines = withoutLeadingNewlines.split("\n")

    // Common leading whitespace of all non-blank lines.
    var common: String? = null
    for (line in lines) {
        if (line.isBlank()) continue
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        common = if (common == null) leading else longestCommonPrefix(common, leading)
        if (common.isEmpty()) break
    }
    val prefix = common ?: ""
    val dedented = lines.joinToString("\n") { line ->
        if (line.isBlank()) line else line.removePrefix(prefix)
    }
    return dedented.trimEnd('\n')
}

/** Wraps [text] to lines no longer than [width] characters, breaking on word boundaries. */
fun mfWrap(text: String, width: Int): String {
    if (text.isEmpty()) return ""
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return ""

    val lines = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    for (word in words) {
        when {
            current.isEmpty() -> current.append(word)
            current.length + 1 + word.length <= width -> current.append(' ').append(word)
            else -> {
                lines.add(current)
                current = StringBuilder(word)
            }
        }
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines.joinToString("\n")
}

/** Default-argument overload — `width=80`. */
fun mfWrap(text: String): String = mfWrap(text, width = 80)

private fun longestCommonPrefix(a: String, b: String): String {
    val end = minOf(a.length, b.length)
    var i = 0
    while (i < end && a[i] == b[i]) i++
    return a.substring(0, i)
}
