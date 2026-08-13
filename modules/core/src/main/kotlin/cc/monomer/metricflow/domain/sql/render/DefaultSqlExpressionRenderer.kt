package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.common.util.mfIndent
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAggregateFunctionExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlBetweenExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlCaseExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlCastToTimestampExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnAliasReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparisonExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlFunction
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIsNullExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlNullExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlRatioComputationExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlStringLiteralExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowFunctionExpression

/**
 * The ANSI-SQL default implementation of [SqlExpressionRenderer]. Dialect renderers (W6)
 * extend this open class and override the methods where the dialect's syntax differs.
 *
 * Port of `metricflow.sql.render.expr_renderer.DefaultSqlExpressionRenderer`. The Python
 * class is closed (no defaults beyond pure ANSI), but dialect subclasses freely override.
 * We keep the same "open class with `protected open fun visit*`" shape.
 */
open class DefaultSqlExpressionRenderer : SqlExpressionRenderer {

    /**
     * The engine description used for engine-feature validation. The default returns
     * `null` so the base class is dialect-agnostic; dialect renderers override.
     */
    protected open val sqlEngine: SqlRenderingEngine? get() = null

    override val doubleDataType: String get() = "DOUBLE"

    override val timestampDataType: String get() = "TIMESTAMP"

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = emptySet()

    override fun visitStringExpr(node: SqlStringExpression): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = node.sqlExpr, bindParameterSet = node.bindParameterSet)

    override fun visitColumnReferenceExpr(
        node: SqlColumnReferenceExpression,
    ): SqlExpressionRenderResult {
        val sql = if (node.shouldRenderTableAlias) {
            "${node.colRef.tableAlias}.${node.colRef.columnName}"
        } else {
            node.colRef.columnName
        }
        return SqlExpressionRenderResult(sql = sql, bindParameterSet = SqlBindParameterSet.EMPTY)
    }

    override fun visitColumnAliasReferenceExpr(
        node: SqlColumnAliasReferenceExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = node.columnAlias, bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitComparisonExpr(
        node: SqlComparisonExpression,
    ): SqlExpressionRenderResult {
        var combined = SqlBindParameterSet.EMPTY

        val left = renderSqlExpr(node.leftExpr)
        combined = combined.merge(left.bindParameterSet)

        val right = renderSqlExpr(node.rightExpr)
        combined = combined.merge(right.bindParameterSet)

        val leftSql = if (node.leftExpr.requiresParenthesis) "(${left.sql})" else left.sql
        val rightSql = if (node.rightExpr.requiresParenthesis) "(${right.sql})" else right.sql

        return SqlExpressionRenderResult(
            sql = "$leftSql ${node.comparison.sql} $rightSql",
            bindParameterSet = combined,
        )
    }

    override fun visitFunctionExpr(
        node: SqlAggregateFunctionExpression,
    ): SqlExpressionRenderResult {
        val argsRendered = node.sqlFunctionArgs.map { renderSqlExpr(it) }
        val combined = argsRendered.fold(SqlBindParameterSet.EMPTY) { acc, r -> acc.merge(r.bindParameterSet) }

        val distinctPrefix = if (SqlFunction.isDistinctAggregation(node.sqlFunction)) "DISTINCT " else ""
        val argsString = argsRendered.joinToString(", ") { it.sql }

        return SqlExpressionRenderResult(
            sql = "${node.sqlFunction.sql}($distinctPrefix$argsString)",
            bindParameterSet = combined,
        )
    }

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        throw IllegalStateException(
            "Default expression render has no percentile implementation - " +
                "an engine-specific renderer should be implemented.",
        )
    }

    override fun visitNullExpr(node: SqlNullExpression): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "NULL", bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitStringLiteralExpr(
        node: SqlStringLiteralExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "'${node.literalValue}'", bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitLogicalExpr(node: SqlLogicalExpression): SqlExpressionRenderResult {
        data class RenderedExpr(
            val result: SqlExpressionRenderResult,
            val requiresParenthesis: Boolean,
        )

        val argsRendered = node.args.map { arg ->
            RenderedExpr(result = renderSqlExpr(arg), requiresParenthesis = arg.requiresParenthesis)
        }

        var combined = SqlBindParameterSet.EMPTY
        // Note: Python's `combined_parameters.merge(...)` discards the return value — same
        // bug-by-design preserved here. The default renderer's logical-expression output is
        // never the carrier of bind params in practice; subclass dialects that do bind
        // params here must override.
        for (rendered in argsRendered) {
            combined.merge(rendered.result.bindParameterSet)
        }

        val canBeRenderedInOneLine = argsRendered.sumOf { it.result.sql.length } < ONE_LINE_LOGICAL_RENDER_LIMIT

        val argsSql = argsRendered.map { rendered ->
            renderLogicalArg(rendered.result, rendered.requiresParenthesis, canBeRenderedInOneLine)
        }

        return SqlExpressionRenderResult(
            sql = argsSql.joinToString(" ${node.operator.sql} "),
            bindParameterSet = combined,
        )
    }

    override fun visitIsNullExpr(node: SqlIsNullExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.arg)
        val argSql = if (node.arg.requiresParenthesis) "(${argRendered.sql})" else argRendered.sql
        return SqlExpressionRenderResult(
            sql = "$argSql IS NULL",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun visitCastToTimestampExpr(
        node: SqlCastToTimestampExpression,
    ): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.arg)
        return SqlExpressionRenderResult(
            sql = "CAST(${argRendered.sql} AS $timestampDataType)",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    /** Validate that [timeGranularity] is supported by the underlying engine, if known. */
    protected fun validateGranularityForEngine(timeGranularity: TimeGranularity) {
        val engine = sqlEngine
        if (engine != null && timeGranularity in engine.unsupportedGranularities) {
            throw UnsupportedEngineFeatureError(
                "${engine.name} does not support time granularity ${timeGranularity.name}.",
            )
        }
    }

    override fun visitDateTruncExpr(node: SqlDateTruncExpression): SqlExpressionRenderResult {
        validateGranularityForEngine(node.timeGranularity)
        val argRendered = renderSqlExpr(node.arg)
        return SqlExpressionRenderResult(
            sql = "DATE_TRUNC('${node.timeGranularity.value}', ${argRendered.sql})",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun visitExtractExpr(node: SqlExtractExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.arg)
        return SqlExpressionRenderResult(
            sql = "EXTRACT(${renderDatePart(node.datePart)} FROM ${argRendered.sql})",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    /**
     * Render a [DatePart] for an `EXTRACT` expression. For [DatePart.DOW] we use the ISO
     * date part to ensure all engines return consistent results.
     */
    protected open fun renderDatePart(datePart: DatePart): String =
        if (datePart == DatePart.DOW) "isodow" else datePart.value

    override fun visitSubtractTimeIntervalExpr(
        node: SqlSubtractTimeIntervalExpression,
    ): SqlExpressionRenderResult {
        val argRendered = node.arg.accept(this)

        var count = node.count
        var granularity = node.granularity
        if (granularity == TimeGranularity.QUARTER) {
            granularity = TimeGranularity.MONTH
            count *= 3
        }
        return SqlExpressionRenderResult(
            sql = "DATEADD(${granularity.value}, -$count, ${argRendered.sql})",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun visitAddTimeExpr(node: SqlAddTimeExpression): SqlExpressionRenderResult {
        var granularity = node.granularity
        var countExpr = node.countExpr
        if (granularity == TimeGranularity.QUARTER) {
            granularity = TimeGranularity.MONTH
            countExpr = SqlArithmeticExpression.create(
                leftExpr = node.countExpr,
                operator = SqlArithmeticOperator.MULTIPLY,
                rightExpr = SqlIntegerExpression.create(3),
            )
        }

        val argRendered = node.arg.accept(this)
        val countRendered = countExpr.accept(this)
        val countSql = if (countExpr.requiresParenthesis) "(${countRendered.sql})" else countRendered.sql

        return SqlExpressionRenderResult(
            sql = "DATEADD(${granularity.value}, $countSql, ${argRendered.sql})",
            bindParameterSet = Mergeable.mergeIterable(
                listOf(argRendered.bindParameterSet, countRendered.bindParameterSet),
                SqlBindParameterSet.EMPTY,
            ),
        )
    }

    override fun visitRatioComputationExpr(
        node: SqlRatioComputationExpression,
    ): SqlExpressionRenderResult {
        val renderedNumerator = renderSqlExpr(node.numerator)
        val renderedDenominator = renderSqlExpr(node.denominator)

        val numeratorSql = "CAST(${renderedNumerator.sql} AS $doubleDataType)"
        val denominatorSql = "CAST(NULLIF(${renderedDenominator.sql}, 0) AS $doubleDataType)"

        val bindParameterSet = SqlBindParameterSet.EMPTY
            .merge(renderedNumerator.bindParameterSet)
            .merge(renderedDenominator.bindParameterSet)

        return SqlExpressionRenderResult(
            sql = "$numeratorSql / $denominatorSql",
            bindParameterSet = bindParameterSet,
        )
    }

    override fun visitBetweenExpr(node: SqlBetweenExpression): SqlExpressionRenderResult {
        val columnRendered = renderSqlExpr(node.columnArg)
        val startRendered = renderSqlExpr(node.startExpr)
        val endRendered = renderSqlExpr(node.endExpr)

        val bindParameterSet = SqlBindParameterSet.EMPTY
            .merge(columnRendered.bindParameterSet)
            .merge(startRendered.bindParameterSet)
            .merge(endRendered.bindParameterSet)

        return SqlExpressionRenderResult(
            sql = "${columnRendered.sql} BETWEEN ${startRendered.sql} AND ${endRendered.sql}",
            bindParameterSet = bindParameterSet,
        )
    }

    override fun visitWindowFunctionExpr(
        node: SqlWindowFunctionExpression,
    ): SqlExpressionRenderResult {
        val functionArgsRendered = node.sqlFunctionArgs.map { renderSqlExpr(it) }
        val partitionByRendered = node.partitionByArgs.map { renderSqlExpr(it) }
        // Python uses dict[result -> arg]; Kotlin keeps insertion-order pairs so duplicate
        // rendered keys collapse (matching dict semantics in Python).
        val orderByRendered = LinkedHashMap<SqlExpressionRenderResult, cc.monomer.metricflow.domain.sql.plan.expr.SqlWindowOrderByArgument>()
        for (arg in node.orderByArgs) {
            orderByRendered[renderSqlExpr(arg.expr)] = arg
        }

        var combined = SqlBindParameterSet.EMPTY
        val allArgs = functionArgsRendered + partitionByRendered + orderByRendered.keys
        for (rendered in allArgs) {
            combined = combined.merge(rendered.bindParameterSet)
        }

        val functionArgsString = functionArgsRendered.joinToString(", ") { it.sql }
        val windowLines = mutableListOf<String>()
        when {
            partitionByRendered.size == 1 ->
                windowLines.add("PARTITION BY ${partitionByRendered[0].sql}")
            partitionByRendered.size > 1 -> {
                windowLines.add("PARTITION BY")
                windowLines.add(
                    mfIndent(
                        partitionByRendered.joinToString("\n, ") { it.sql },
                        indentLevel = 1,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
            }
        }
        when {
            orderByRendered.size == 1 -> {
                val (renderedResult, orderByArg) = orderByRendered.entries.first()
                val suffix = orderByArg.suffix
                windowLines.add(
                    "ORDER BY " + renderedResult.sql + (if (suffix.isNotEmpty()) " $suffix" else ""),
                )
            }
            orderByRendered.size > 1 -> {
                windowLines.add("ORDER BY")
                windowLines.add(
                    mfIndent(
                        orderByRendered.entries.joinToString("\n, ") { (renderedResult, orderByArg) ->
                            val suffix = orderByArg.suffix
                            renderedResult.sql + (if (suffix.isNotEmpty()) " $suffix" else "")
                        },
                        indentLevel = 1,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
            }
        }

        if (orderByRendered.isNotEmpty() && node.sqlFunction.allowsFrameClause) {
            windowLines.add("ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING")
        }

        val windowString = windowLines.joinToString("\n")

        return if (windowLines.size <= 1) {
            SqlExpressionRenderResult(
                sql = "${node.sqlFunction.sql}($functionArgsString) OVER ($windowString)",
                bindParameterSet = combined,
            )
        } else {
            val indented = mfIndent(windowString, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT)
            SqlExpressionRenderResult(
                sql = "${node.sqlFunction.sql}($functionArgsString) OVER (\n$indented\n)",
                bindParameterSet = combined,
            )
        }
    }

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "UUID()", bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitCaseExpr(node: SqlCaseExpression): SqlExpressionRenderResult {
        val sql = buildString {
            append("CASE\n")
            for ((whenExpr, thenExpr) in node.whenToThenExprs) {
                append(
                    mfIndent(
                        "WHEN ${renderSqlExpr(whenExpr).sql}\n",
                        indentLevel = 1,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
                append(
                    mfIndent(
                        "THEN ${renderSqlExpr(thenExpr).sql}\n",
                        indentLevel = 2,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
            }
            if (node.elseExpr != null) {
                append(
                    mfIndent(
                        "ELSE ${renderSqlExpr(node.elseExpr!!).sql}\n",
                        indentLevel = 1,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
            }
            append("END")
        }
        return SqlExpressionRenderResult(sql = sql, bindParameterSet = SqlBindParameterSet.EMPTY)
    }

    override fun visitArithmeticExpr(
        node: SqlArithmeticExpression,
    ): SqlExpressionRenderResult {
        val left = renderSqlExpr(node.leftExpr)
        val right = renderSqlExpr(node.rightExpr)
        // Note: Python's `visit_arithmetic_expr` discards arg bind params — preserved here.
        return SqlExpressionRenderResult(
            sql = "${left.sql} ${node.operator.sql} ${right.sql}",
            bindParameterSet = SqlBindParameterSet.EMPTY,
        )
    }

    override fun visitIntegerExpr(node: SqlIntegerExpression): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = node.integerValue.toString(), bindParameterSet = SqlBindParameterSet.EMPTY)

    companion object {
        // Sum of arg-sql lengths above which a logical expression renders multi-line.
        private const val ONE_LINE_LOGICAL_RENDER_LIMIT: Int = 60

        /**
         * Render a single argument inside a logical expression chain. Short combined
         * expressions stay on one line; long ones split with the argument indented inside
         * parentheses on its own line. Mirrors Python's `_render_logical_arg`.
         */
        private fun renderLogicalArg(
            argRendered: SqlExpressionRenderResult,
            requiresParenthesis: Boolean,
            renderInOneLine: Boolean,
        ): String {
            if (renderInOneLine) {
                return if (requiresParenthesis) "(${argRendered.sql})" else argRendered.sql
            }
            val indented = mfIndent(argRendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT)
            return "(\n$indented\n)"
        }
    }
}
