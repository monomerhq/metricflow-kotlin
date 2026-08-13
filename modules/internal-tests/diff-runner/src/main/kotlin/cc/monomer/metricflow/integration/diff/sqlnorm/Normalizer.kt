package cc.monomer.metricflow.integration.diff.sqlnorm

/**
 * Kotlin port of `harness/sql_norm/normalizer.py`.
 *
 * Each rule must be semantic-preserving and is documented under
 * `harness/sql_norm/rules/<name>.md`. We keep the same three seed rules in the
 * same order so a SQL string normalized in Kotlin is byte-identical to a SQL
 * string normalized by the Python oracle.
 *
 * | Rule | Description |
 * |------|-------------|
 * | `normalize_line_endings`     | CRLF / CR → LF (line breaks are not SQL-significant). |
 * | `trim_trailing_whitespace`   | Strip trailing spaces and tabs on each line (cosmetic only). |
 * | `collapse_blank_lines`       | Runs of 3+ newlines → 2 (max one blank line in a row). |
 *
 * After the rule pipeline runs we also strip any trailing newlines so a
 * trailing-blank-line discrepancy between sources never matters.
 */
object SqlNormalizer {

    /** Normalize one SQL string. Order: line-endings, trim, collapse, rstrip("\n"). */
    fun normalize(sql: String): String {
        val pipelined = rules.fold(sql) { acc, rule -> rule.apply(acc) }
        return pipelined.trimEnd('\n')
    }

    private data class Rule(val name: String, val apply: (String) -> String)

    private val rules: List<Rule> = listOf(
        Rule("normalize_line_endings") { it.replace("\r\n", "\n").replace("\r", "\n") },
        Rule("trim_trailing_whitespace") { input ->
            input.split('\n').joinToString("\n") { line -> line.trimEnd(' ', '\t') }
        },
        Rule("collapse_blank_lines") { it.replace(BLANK_RUN, "\n\n") },
    )

    private val BLANK_RUN: Regex = Regex("\n{3,}")
}
