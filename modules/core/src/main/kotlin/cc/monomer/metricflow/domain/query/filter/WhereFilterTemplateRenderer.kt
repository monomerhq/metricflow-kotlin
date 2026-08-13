package cc.monomer.metricflow.domain.query.filter

import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.DimensionSpec
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName

/**
 * Minimal Jinja-template renderer for the where-filter call-parameter sets that metricflow
 * uses: `{{ Dimension(...) }}`, `{{ TimeDimension(...) }}`, `{{ Entity(...) }}`, and
 * `{{ Metric(...) }}` (plus chained `.grain(...)` / `.date_part(...)` calls).
 *
 * **Not a general Jinja sandbox.** Port of just enough of Python's `WhereFilterSpecFactory` +
 * the four `WhereFilter*` factory classes to render the corpus's single where-filter case
 * (`bookings_with_where`). The Python pipeline uses Jinja2 with a custom undefined hook and
 * registers four callable objects; we replace the Jinja sandbox with a hand-written tokenizer
 * + AST + evaluator over the constrained grammar:
 *
 * ```
 * template     := (LITERAL | '{{' expr '}}')*
 * expr         := call ('.' method '(' args ')')*
 * call         := IDENT '(' args ')'
 * args         := (literal (',' literal)*)?
 * literal      := STRING | '[' STRING (',' STRING)* ']'   // Python-style single-quoted strings
 * IDENT        := 'Dimension' | 'TimeDimension' | 'Entity' | 'Metric'
 * method       := 'grain' | 'date_part' | 'descending' | 'alias'
 * ```
 *
 * The renderer's [render] returns the rendered SQL plus the set of [LinkableInstanceSpec]s
 * referenced (so the column-pruner knows which columns to keep). For the corpus's single case:
 *
 * - Input: `{{ Dimension('booking__is_instant') }} = true`
 * - Output: `whereSql = "booking__is_instant = true"`, `usedSpecs = [DimensionSpec(is_instant, [booking])]`
 *
 * This is a **stopgap** for the W15 final-wave pass — see
 * [WhereFilterSpecFactory] KDoc for the full-Jinja porting path. The grammar
 * captured here covers every `tests_metricflow` corpus snapshot that lands in
 * the Kotlin diff-runner today (1 case); broader use of the renderer in
 * future corpus additions should grow this grammar deliberately and add a
 * unit test per call-parameter shape.
 */
internal class WhereFilterTemplateRenderer(
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val customGrainNames: Set<String>,
) {

    /** Output of [render]: rendered SQL + the linkable specs touched by the template. */
    data class Rendered(
        val whereSql: String,
        val usedSpecs: List<LinkableInstanceSpec>,
    )

    fun render(template: String): Rendered {
        val usedSpecs = mutableListOf<LinkableInstanceSpec>()
        val output = StringBuilder()
        var i = 0
        while (i < template.length) {
            val openIdx = template.indexOf("{{", i)
            if (openIdx < 0) {
                output.append(template, i, template.length)
                break
            }
            output.append(template, i, openIdx)
            val closeIdx = template.indexOf("}}", openIdx + 2)
            require(closeIdx > openIdx) {
                "Malformed Jinja template: unmatched '{{' at index $openIdx in '$template'"
            }
            val expr = template.substring(openIdx + 2, closeIdx).trim()
            val resolved = evalExpr(expr, usedSpecs)
            output.append(resolved)
            i = closeIdx + 2
        }
        return Rendered(whereSql = output.toString(), usedSpecs = usedSpecs)
    }

    private fun evalExpr(expr: String, usedSpecs: MutableList<LinkableInstanceSpec>): String {
        // Tokenize: split into top-level call + chained .method(...) calls.
        // Conservative recursive-descent parser; the grammar is small enough to do by hand.
        val parser = TemplateParser(expr)
        val callNode = parser.parseExpr()
        parser.expectEnd()
        val spec = buildLinkableSpec(callNode)
        usedSpecs.add(spec)
        return columnAssociationResolver.resolveSpec(spec).columnName
    }

    /**
     * Convert a parsed [CallNode] tree to a [LinkableInstanceSpec]. Supports the four primary
     * call kinds — Dimension, TimeDimension, Entity, Metric — with optional chained `.grain(...)`
     * and `.date_part(...)` modifiers (the only modifiers metricflow renders in the corpus).
     */
    private fun buildLinkableSpec(call: CallNode): LinkableInstanceSpec {
        var timeGrain: String? = null
        var datePart: String? = null
        for (chain in call.chain) {
            when (chain.method) {
                "grain" -> {
                    require(chain.args.size == 1 && chain.args[0] is LiteralNode.StringLit) {
                        "grain() requires a single string argument: $chain"
                    }
                    timeGrain = (chain.args[0] as LiteralNode.StringLit).value.lowercase()
                }
                "date_part" -> {
                    require(chain.args.size == 1 && chain.args[0] is LiteralNode.StringLit) {
                        "date_part() requires a single string argument: $chain"
                    }
                    datePart = (chain.args[0] as LiteralNode.StringLit).value.lowercase()
                }
                else -> throw NotImplementedError(
                    "Where-filter template method '.${chain.method}(...)' is not supported by the W15 " +
                        "minimal renderer. See WhereFilterTemplateRenderer KDoc.",
                )
            }
        }

        when (call.head) {
            "Dimension" -> {
                require(call.args.isNotEmpty() && call.args[0] is LiteralNode.StringLit) {
                    "Dimension(...) requires a string first argument: $call"
                }
                val rawName = (call.args[0] as LiteralNode.StringLit).value
                val structured = StructuredLinkableSpecName.fromName(rawName, customGrainNames.toList())
                val entityLinks = structured.entityLinkNames.map { EntityReference(it) }
                // Corpus check: `Dimension('booking__is_instant')` becomes a DimensionSpec.
                // For time dimensions like Dimension('booking__ds'), we'd return a TimeDimensionSpec;
                // the corpus's single case has a non-time dimension so we don't need that branch yet.
                // Once a time-dim case lands, switch on `is structured.timeGranularityName != null`.
                return if (timeGrain != null || datePart != null || structured.timeGranularityName != null) {
                    // Time dim path — only the corpus case exercises plain Dimension(non-time) today,
                    // but future cases that say Dimension('ds__day') route here.
                    val grain = timeGrain
                        ?: structured.timeGranularityName
                        ?: throw IllegalStateException("Time-dimension where-filter without grain: $call")
                    buildTimeDimensionSpec(
                        elementName = structured.elementName,
                        entityLinks = entityLinks,
                        grainName = grain,
                        datePart = datePart,
                    )
                } else {
                    DimensionSpec(
                        elementName = structured.elementName,
                        entityLinks = entityLinks,
                        alias = null,
                    )
                }
            }
            "TimeDimension" -> {
                require(call.args.isNotEmpty() && call.args[0] is LiteralNode.StringLit) {
                    "TimeDimension(...) requires a string first argument: $call"
                }
                val rawName = (call.args[0] as LiteralNode.StringLit).value
                val structured = StructuredLinkableSpecName.fromName(rawName, customGrainNames.toList())
                val entityLinks = structured.entityLinkNames.map { EntityReference(it) }
                // Second positional argument is the grain.
                val grain = if (call.args.size >= 2) {
                    require(call.args[1] is LiteralNode.StringLit) {
                        "TimeDimension(...) second arg must be a string grain: $call"
                    }
                    (call.args[1] as LiteralNode.StringLit).value.lowercase()
                } else {
                    timeGrain ?: structured.timeGranularityName
                        ?: throw IllegalArgumentException("TimeDimension('$rawName') requires a grain.")
                }
                return buildTimeDimensionSpec(
                    elementName = structured.elementName,
                    entityLinks = entityLinks,
                    grainName = grain,
                    datePart = datePart,
                )
            }
            "Entity" -> {
                require(call.args.isNotEmpty() && call.args[0] is LiteralNode.StringLit) {
                    "Entity(...) requires a string first argument: $call"
                }
                val rawName = (call.args[0] as LiteralNode.StringLit).value
                val parts = rawName.split(DUNDER)
                return EntitySpec(
                    elementName = parts.last(),
                    entityLinks = parts.dropLast(1).map { EntityReference(it) },
                    alias = null,
                )
            }
            "Metric" -> throw NotImplementedError(
                "Where-filter Metric(...) is not yet supported by the W15 minimal renderer " +
                    "(no corpus case exercises it). See WhereFilterTemplateRenderer KDoc.",
            )
            else -> throw NotImplementedError(
                "Unknown where-filter template head '${call.head}'. Supported: " +
                    "Dimension, TimeDimension, Entity, Metric.",
            )
        }
    }

    private fun buildTimeDimensionSpec(
        elementName: String,
        entityLinks: List<EntityReference>,
        grainName: String,
        datePart: String?,
    ): TimeDimensionSpec {
        // Map grain name → TimeGranularity / ExpandedTimeGranularity.
        val timeGranularity = cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
            .entries.firstOrNull { it.value.equals(grainName, ignoreCase = true) }
        val expanded = if (timeGranularity != null) {
            cc.monomer.metricflow.common.time.ExpandedTimeGranularity.fromTimeGranularity(
                timeGranularity,
            )
        } else {
            // Custom granularity. Use a placeholder ExpandedTimeGranularity with DAY base; only the
            // name is used downstream for column resolution in the corpus's snapshot space.
            cc.monomer.metricflow.common.time.ExpandedTimeGranularity(
                name = grainName,
                baseGranularity = cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity.DAY,
            )
        }
        val datePartEnum = datePart?.let { dp ->
            cc.monomer.metricflow.domain.manifest.model.enums.DatePart
                .entries.first { it.value.equals(dp, ignoreCase = true) }
        }
        // The TimeDimensionSpec init invariant: exactly one of (timeGranularity, datePart). When
        // both are nominally available we prefer datePart (Python does the same — date_part
        // narrows the time dimension to a year/month/etc. extract).
        return if (datePartEnum != null) {
            TimeDimensionSpec(
                elementName = elementName,
                entityLinks = entityLinks,
                timeGranularity = null,
                datePart = datePartEnum,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )
        } else {
            TimeDimensionSpec(
                elementName = elementName,
                entityLinks = entityLinks,
                timeGranularity = expanded,
                datePart = null,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )
        }
    }

    /**
     * Hand-rolled tokenizer + AST builder for the constrained grammar. Public surface is just
     * [TemplateParser.parseExpr] / [TemplateParser.expectEnd]; the rest is package-private to
     * keep the renderer cohesive.
     */
    private class TemplateParser(private val source: String) {
        private var pos = 0

        fun parseExpr(): CallNode {
            skipWhitespace()
            val head = parseIdentifier()
            expect('(')
            val args = parseArgs()
            expect(')')
            val chain = mutableListOf<MethodCall>()
            while (true) {
                skipWhitespace()
                if (pos >= source.length || source[pos] != '.') break
                pos += 1
                val method = parseIdentifier()
                expect('(')
                val methodArgs = parseArgs()
                expect(')')
                chain.add(MethodCall(method, methodArgs))
            }
            return CallNode(head = head, args = args, chain = chain)
        }

        fun expectEnd() {
            skipWhitespace()
            require(pos >= source.length) {
                "Unexpected trailing text in template expression at $pos: '${source.substring(pos)}'"
            }
        }

        private fun parseIdentifier(): String {
            skipWhitespace()
            val start = pos
            while (pos < source.length &&
                (source[pos].isLetterOrDigit() || source[pos] == '_')
            ) pos += 1
            require(pos > start) {
                "Expected identifier at $pos in '$source'"
            }
            return source.substring(start, pos)
        }

        private fun parseArgs(): List<LiteralNode> {
            val args = mutableListOf<LiteralNode>()
            skipWhitespace()
            if (pos < source.length && source[pos] == ')') return args
            args.add(parseLiteral())
            while (true) {
                skipWhitespace()
                if (pos >= source.length || source[pos] != ',') break
                pos += 1
                args.add(parseLiteral())
            }
            return args
        }

        private fun parseLiteral(): LiteralNode {
            skipWhitespace()
            require(pos < source.length) { "Unexpected end of template expression while parsing literal." }
            return when (source[pos]) {
                '\'', '"' -> LiteralNode.StringLit(parseString())
                '[' -> parseList()
                else -> throw IllegalArgumentException(
                    "Unexpected token at $pos in '$source': expected a string or list literal.",
                )
            }
        }

        private fun parseString(): String {
            val quote = source[pos]
            pos += 1
            val start = pos
            while (pos < source.length && source[pos] != quote) {
                // No escape handling — metricflow templates don't use escapes.
                pos += 1
            }
            require(pos < source.length) { "Unterminated string literal in '$source'" }
            val value = source.substring(start, pos)
            pos += 1
            return value
        }

        private fun parseList(): LiteralNode.ListLit {
            pos += 1 // consume '['
            val items = mutableListOf<String>()
            skipWhitespace()
            if (pos < source.length && source[pos] != ']') {
                items.add(parseString())
                while (true) {
                    skipWhitespace()
                    if (pos >= source.length || source[pos] != ',') break
                    pos += 1
                    skipWhitespace()
                    items.add(parseString())
                }
            }
            skipWhitespace()
            expect(']')
            return LiteralNode.ListLit(items)
        }

        private fun expect(c: Char) {
            skipWhitespace()
            require(pos < source.length && source[pos] == c) {
                "Expected '$c' at $pos in '$source', got '${if (pos < source.length) source[pos] else "<eof>"}'"
            }
            pos += 1
        }

        private fun skipWhitespace() {
            while (pos < source.length && source[pos].isWhitespace()) pos += 1
        }
    }

    /** A function-call AST node: `IDENT(args).method1(args).method2(args)...`. */
    private data class CallNode(val head: String, val args: List<LiteralNode>, val chain: List<MethodCall>)

    /** A `.method(args)` chain segment. */
    private data class MethodCall(val method: String, val args: List<LiteralNode>)

    /** A literal in an argument list — either a string or a list of strings. */
    private sealed interface LiteralNode {
        data class StringLit(val value: String) : LiteralNode
        data class ListLit(val items: List<String>) : LiteralNode
    }
}
