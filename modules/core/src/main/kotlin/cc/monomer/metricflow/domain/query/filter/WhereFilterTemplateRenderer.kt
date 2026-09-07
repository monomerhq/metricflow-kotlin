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
 * Renders constrained where-filter object calls into column names and referenced specs.
 *
 * Ports the Dimension, TimeDimension and Entity argument binding from upstream
 * `parsing/text_input/rendering_helper.py` and entity-path composition from
 * `parsing/where_filter/parameter_set_factory.py`. Positional and named arguments
 * support string, string-list and None literals; this is not a general Jinja runtime.
 * Grain and date-part method chains preserve their existing rendering behavior.
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

    /** Binds each call's arguments before resolving its entity path and time modifiers. */
    private fun buildLinkableSpec(call: CallNode): LinkableInstanceSpec {
        val parameterNames = when (call.head) {
            "Dimension" -> listOf("name", "entity_path")
            "TimeDimension" -> listOf(
                "time_dimension_name", "time_granularity_name", "entity_path", "descending", "date_part_name",
            )
            "Entity" -> listOf("entity_name", "entity_path")
            else -> throw NotImplementedError("Unsupported where-filter call '${call.head}'.")
        }
        val arguments = bindArguments(call.args, parameterNames)
        require("descending" !in arguments) { "Where-filter descending is not supported." }
        val rawName = (arguments[parameterNames.first()] as? LiteralNode.StringLit)?.value
            ?: throw IllegalArgumentException("${call.head}(...) requires a string name.")
        val entityPath = when (val path = arguments["entity_path"]) {
            null -> emptyList()
            is LiteralNode.ListLit -> path.items.map { EntityReference(it) }
            else -> throw IllegalArgumentException("entity_path must be a list of strings.")
        }
        val suppliedGrain = optionalString(arguments, "time_granularity_name")
        var timeGrain: String? = null
        var datePart: String? = optionalString(arguments, "date_part_name")
        for (chain in call.chain) {
            when (chain.method) {
                "grain" -> {
                    require(chain.args.size == 1 && chain.args[0].name == null && chain.args[0].value is LiteralNode.StringLit) {
                        "grain() requires a single string argument: $chain"
                    }
                    timeGrain = (chain.args[0].value as LiteralNode.StringLit).value.lowercase()
                }
                "date_part" -> {
                    require(chain.args.size == 1 && chain.args[0].name == null && chain.args[0].value is LiteralNode.StringLit) {
                        "date_part() requires a single string argument: $chain"
                    }
                    datePart = (chain.args[0].value as LiteralNode.StringLit).value.lowercase()
                }
                else -> throw NotImplementedError(
                    "Unsupported where-filter template method '.${chain.method}(...)'.",
                )
            }
        }

        when (call.head) {
            "Dimension" -> {
                val structured = StructuredLinkableSpecName.fromName(rawName, customGrainNames.toList())
                val entityLinks = entityPath + structured.entityLinkNames.map { EntityReference(it) }
                return if (timeGrain != null || datePart != null || structured.timeGranularityName != null) {
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
                val structured = StructuredLinkableSpecName.fromName(rawName, customGrainNames.toList())
                val entityLinks = entityPath + structured.entityLinkNames.map { EntityReference(it) }
                require(suppliedGrain == null || structured.timeGranularityName == null ||
                    suppliedGrain == structured.timeGranularityName) {
                    "TimeDimension name and argument specify different grains."
                }
                val grain = timeGrain ?: suppliedGrain ?: structured.timeGranularityName
                    ?: throw IllegalArgumentException("TimeDimension('$rawName') requires a grain.")
                return buildTimeDimensionSpec(
                    elementName = structured.elementName,
                    entityLinks = entityLinks,
                    grainName = grain,
                    datePart = datePart,
                )
            }
            "Entity" -> {
                require(call.chain.isEmpty()) { "Entity does not support time modifiers." }
                val parts = rawName.split(DUNDER)
                return EntitySpec(
                    elementName = parts.last(),
                    entityLinks = entityPath + parts.dropLast(1).map { EntityReference(it) },
                    alias = null,
                )
            }
            else -> throw NotImplementedError("Unsupported where-filter call '${call.head}'.")
        }
    }

    private fun bindArguments(args: List<ArgumentNode>, names: List<String>): Map<String, LiteralNode> {
        val bound = linkedMapOf<String, LiteralNode>()
        var namedSeen = false
        for ((index, argument) in args.withIndex()) {
            val name = argument.name ?: run {
                require(!namedSeen) { "Positional argument cannot follow a named argument." }
                require(index < names.size) { "Too many arguments; expected ${names.joinToString()}." }
                names[index]
            }
            if (argument.name != null) namedSeen = true
            require(name in names) { "Unknown argument '$name'." }
            require(name !in bound) { "Duplicate argument '$name'." }
            bound[name] = argument.value
        }
        return bound
    }

    private fun optionalString(arguments: Map<String, LiteralNode>, name: String): String? =
        when (val argument = arguments[name]) {
            null, LiteralNode.NoneLit -> null
            is LiteralNode.StringLit -> argument.value.lowercase()
            else -> throw IllegalArgumentException("$name must be a string or None.")
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

        private fun parseArgs(): List<ArgumentNode> {
            val args = mutableListOf<ArgumentNode>()
            while (true) {
                skipWhitespace()
                if (pos < source.length && source[pos] == ')') return args
                val name = if (pos < source.length && (source[pos].isLetter() || source[pos] == '_') &&
                    !source.startsWith("None", pos)) {
                    val identifier = parseIdentifier()
                    expect('=')
                    identifier
                } else null
                args.add(ArgumentNode(name, parseLiteral()))
                skipWhitespace()
                if (pos >= source.length || source[pos] != ',') return args
                pos += 1
            }
        }

        private fun parseLiteral(): LiteralNode {
            skipWhitespace()
            require(pos < source.length) { "Unexpected end of template expression while parsing literal." }
            return when (source[pos]) {
                '\'', '"' -> LiteralNode.StringLit(parseString())
                '[' -> parseList()
                'N' -> {
                    require(source.startsWith("None", pos)) { "Expected None at $pos." }
                    pos += 4
                    LiteralNode.NoneLit
                }
                else -> throw IllegalArgumentException(
                    "Unexpected token at $pos in '$source': expected a string or list literal.",
                )
            }
        }

        private fun parseString(): String {
            skipWhitespace()
            require(pos < source.length && source[pos] in "\"'") { "Expected a string literal at $pos in '$source'." }
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
                    if (pos < source.length && source[pos] == ']') break
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
    private data class CallNode(val head: String, val args: List<ArgumentNode>, val chain: List<MethodCall>)

    /** A `.method(args)` chain segment. */
    private data class MethodCall(val method: String, val args: List<ArgumentNode>)

    private data class ArgumentNode(val name: String?, val value: LiteralNode)

    /** A literal in an argument list — either a string or a list of strings. */
    private sealed interface LiteralNode {
        data object NoneLit : LiteralNode
        data class StringLit(val value: String) : LiteralNode
        data class ListLit(val items: List<String>) : LiteralNode
    }
}
