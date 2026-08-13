package cc.monomer.metricflow.infrastructure.sql.render.trino

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlBetweenExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine

/**
 * Expression renderer for the Trino engine.
 *
 * Port of `metricflow.sql.render.trino.TrinoSqlExpressionRenderer`.
 *
 * Trino-specific overrides:
 *
 * | Method | Trino divergence |
 * |---|---|
 * | `supportedPercentileFunctionTypes` | only `APPROXIMATE_CONTINUOUS` |
 * | `visitGenerateUuidExpr` | `uuid()` (lowercase) |
 * | `visitSubtractTimeIntervalExpr` | `DATE_ADD('<gran>', -count, arg)` syntax |
 * | `visitAddTimeExpr` | same `DATE_ADD` style for additions |
 * | `visitPercentileExpr` | uses `approx_percentile`; rejects continuous/discrete |
 * | `visitBetweenExpr` | wraps timestamp literals with `timestamp` prefix |
 * | `renderDatePart` | `DOW` → `DAY_OF_WEEK` |
 */
open class TrinoSqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = TrinoEngine

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS)

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "uuid()", bindParameterSet = SqlBindParameterSet.EMPTY)

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
            sql = "DATE_ADD('${granularity.value}', -$count, ${argRendered.sql})",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun visitAddTimeExpr(node: SqlAddTimeExpression): SqlExpressionRenderResult {
        var granularity = node.granularity
        val countExpr = node.countExpr
        if (granularity == TimeGranularity.QUARTER) {
            granularity = TimeGranularity.MONTH
            // Python builds the multiply-by-3 expression here but then never assigns it
            // to `count_expr` — same bug-by-design preserved (rendered count is still the
            // original countExpr).
            SqlArithmeticExpression.create(
                leftExpr = node.countExpr,
                operator = SqlArithmeticOperator.MULTIPLY,
                rightExpr = SqlIntegerExpression.create(3),
            )
        }

        val argRendered = node.arg.accept(this)
        val countRendered = countExpr.accept(this)
        val countSql = if (countExpr.requiresParenthesis) "(${countRendered.sql})" else countRendered.sql

        return SqlExpressionRenderResult(
            sql = "DATE_ADD('${granularity.value}', $countSql, ${argRendered.sql})",
            bindParameterSet = Mergeable.mergeIterable(
                listOf(argRendered.bindParameterSet, countRendered.bindParameterSet),
                SqlBindParameterSet.EMPTY,
            ),
        )
    }

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.orderByArg)
        val params = argRendered.bindParameterSet
        val percentile = node.percentileArgs.percentile

        return when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> SqlExpressionRenderResult(
                sql = "approx_percentile(${argRendered.sql}, $percentile)",
                bindParameterSet = params,
            )
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
            SqlPercentileFunctionType.DISCRETE,
            SqlPercentileFunctionType.CONTINUOUS,
            -> throw RuntimeException(
                "Discrete, Continuous and Approximate discrete percentile aggregates are not supported for Trino. " +
                    "Set use_approximate_percentile and disable use_discrete_percentile in all percentile simple-metrics.",
            )
        }
    }

    override fun visitBetweenExpr(node: SqlBetweenExpression): SqlExpressionRenderResult {
        val renderedColumnArg = renderSqlExpr(node.columnArg)
        val renderedStartExpr = renderSqlExpr(node.startExpr)
        val renderedEndExpr = renderSqlExpr(node.endExpr)

        val bindParameterSet = SqlBindParameterSet.EMPTY
            .merge(renderedColumnArg.bindParameterSet)
            .merge(renderedStartExpr.bindParameterSet)
            .merge(renderedEndExpr.bindParameterSet)

        val sql = if (looksLikeTimestampLiteral(renderedStartExpr.sql)) {
            "${renderedColumnArg.sql} BETWEEN timestamp ${renderedStartExpr.sql} AND timestamp ${renderedEndExpr.sql}"
        } else {
            "${renderedColumnArg.sql} BETWEEN ${renderedStartExpr.sql} AND ${renderedEndExpr.sql}"
        }
        return SqlExpressionRenderResult(sql = sql, bindParameterSet = bindParameterSet)
    }

    override fun renderDatePart(datePart: DatePart): String =
        if (datePart == DatePart.DOW) "DAY_OF_WEEK" else datePart.value

    companion object {
        /** Engine description used by the W5 base for granularity validation. */
        val TrinoEngine: SqlRenderingEngine = cc.monomer.metricflow.domain.sql.render
            .DialectSqlRenderingEngine(name = "TRINO", unsupportedGranularities = emptySet())

        /**
         * Approximates Python's `dateutil.parser.parse(...)` truthiness check used in
         * `TrinoSqlExpressionRenderer.visit_between_expr`: returns true if the SQL
         * fragment plausibly represents a date/timestamp literal. Mirrors Python's
         * permissive behaviour — e.g. a quoted `'2020-01-01'` or `'2020-01-01 00:00:00'`
         * literal is matched, but a bare numeric literal like `5` is not.
         */
        internal fun looksLikeTimestampLiteral(sql: String): Boolean {
            val unquoted = sql.trim().removeSurrounding("'")
            if (unquoted == sql.trim()) {
                // Not a quoted literal — Python's `parse(...)` would happily eat raw ints
                // (returning today's date at hour=int), but that path is not exercised in
                // practice (BETWEEN's start_expr is always a string-literal expression in
                // metricflow's dataflow). We conservatively decline here.
                return false
            }
            return ISO_DATE_LIKE.containsMatchIn(unquoted)
        }

        private val ISO_DATE_LIKE: Regex = Regex("""^\d{4}-\d{2}-\d{2}([T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})?)?$""")
    }
}
