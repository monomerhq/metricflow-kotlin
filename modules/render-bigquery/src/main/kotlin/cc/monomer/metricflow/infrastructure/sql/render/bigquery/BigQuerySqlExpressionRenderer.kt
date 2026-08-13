package cc.monomer.metricflow.infrastructure.sql.render.bigquery

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlCastToTimestampExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine

/**
 * Expression renderer for the BigQuery engine.
 *
 * Port of `metricflow.sql.render.big_query.BigQuerySqlExpressionRenderer`.
 *
 * BigQuery-specific overrides:
 *
 * | Method | BigQuery divergence |
 * |---|---|
 * | `doubleDataType` | `FLOAT64` |
 * | `timestampDataType` | `DATETIME` (no time-zone — see Python docstring) |
 * | `supportedPercentileFunctionTypes` | `APPROXIMATE_CONTINUOUS` only |
 * | `renderGroupByExpr` | references the SELECT alias instead of repeating the expression |
 * | `visitPercentileExpr` | uses `APPROX_QUANTILES` with a `Fraction`-driven OFFSET |
 * | `visitCastToTimestampExpr` | overridden so casts use BigQuery's `DATETIME` type |
 * | `visitDateTruncExpr` | `DATETIME_TRUNC(arg, gran)` (opposite arg order from Snowflake/Redshift) |
 * | `renderDatePart` | `DOY` → `dayofyear`, `DOW` → `dayofweek` |
 * | `visitExtractExpr` | post-processes `DOW` to renormalise to ISO 1..7 |
 * | `visitSubtractTimeIntervalExpr` | `DATE_SUB(CAST(arg AS DATETIME), INTERVAL count gran)` |
 * | `visitAddTimeExpr` | mirror of `DATE_SUB` shape for additions |
 * | `visitGenerateUuidExpr` | `GENERATE_UUID()` |
 */
open class BigQuerySqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = BigQueryEngine

    override val doubleDataType: String get() = "FLOAT64"

    override val timestampDataType: String get() = "DATETIME"

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS)

    /**
     * BigQuery requires `GROUP BY` to reference column aliases (not repeat the expression).
     * Mirrors `metricflow.sql.render.big_query.BigQuerySqlExpressionRenderer.render_group_by_expr`.
     */
    override fun renderGroupByExpr(groupByColumn: SqlSelectColumn): SqlExpressionRenderResult =
        SqlExpressionRenderResult(
            sql = groupByColumn.columnAlias,
            bindParameterSet = groupByColumn.expr.bindParameterSet,
        )

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> {
                val argRendered = renderSqlExpr(node.orderByArg)
                val params = argRendered.bindParameterSet
                val percentile = node.percentileArgs.percentile

                val (numerator, denominator) = doubleToLimitedFraction(percentile, LIMIT_DENOMINATOR_DEFAULT)

                return SqlExpressionRenderResult(
                    sql = "APPROX_QUANTILES(${argRendered.sql}, $denominator)[OFFSET($numerator)]",
                    bindParameterSet = params,
                )
            }
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
            SqlPercentileFunctionType.CONTINUOUS,
            SqlPercentileFunctionType.DISCRETE,
            -> throw UnsupportedEngineFeatureError(
                "Only approximate continous percentile aggregations are supported for BigQuery. Set " +
                    "use_approximate_percentile and disable use_discrete_percentile in all percentile simple-metrics.",
            )
        }
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

    override fun visitDateTruncExpr(node: SqlDateTruncExpression): SqlExpressionRenderResult {
        // Validate granularity against engine support (same as base).
        if (BigQueryEngine.unsupportedGranularities.contains(node.timeGranularity)) {
            throw UnsupportedEngineFeatureError(
                "${BigQueryEngine.name} does not support time granularity ${node.timeGranularity.name}.",
            )
        }
        val argRendered = renderSqlExpr(node.arg)
        val prefix = if (node.timeGranularity == TimeGranularity.WEEK) "iso" else ""
        return SqlExpressionRenderResult(
            sql = "DATETIME_TRUNC(${argRendered.sql}, $prefix${node.timeGranularity.value})",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun renderDatePart(datePart: DatePart): String = when (datePart) {
        DatePart.DOY -> "dayofyear"
        DatePart.DOW -> "dayofweek"
        else -> super.renderDatePart(datePart)
    }

    override fun visitExtractExpr(node: SqlExtractExpression): SqlExpressionRenderResult {
        val baseResult = super.visitExtractExpr(node)
        if (node.datePart != DatePart.DOW) return baseResult

        val extractStmt = baseResult.sql
        // BigQuery returns 1 (Sunday) - 7 (Saturday); renormalise to ISO 1 (Monday) - 7 (Sunday).
        val caseExpr = "IF($extractStmt = 1, 7, $extractStmt - 1)"
        return SqlExpressionRenderResult(sql = caseExpr, bindParameterSet = baseResult.bindParameterSet)
    }

    override fun visitSubtractTimeIntervalExpr(
        node: SqlSubtractTimeIntervalExpression,
    ): SqlExpressionRenderResult {
        val column = node.arg.accept(this)
        return SqlExpressionRenderResult(
            sql = "DATE_SUB(CAST(${column.sql} AS $timestampDataType), INTERVAL ${node.count} ${node.granularity.value})",
            bindParameterSet = column.bindParameterSet,
        )
    }

    override fun visitAddTimeExpr(node: SqlAddTimeExpression): SqlExpressionRenderResult {
        val column = node.arg.accept(this)
        val count = node.countExpr.accept(this)
        return SqlExpressionRenderResult(
            sql = "DATE_ADD(CAST(${column.sql} AS $timestampDataType), INTERVAL ${count.sql} ${node.granularity.value})",
            bindParameterSet = column.bindParameterSet.merge(count.bindParameterSet),
        )
    }

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "GENERATE_UUID()", bindParameterSet = SqlBindParameterSet.EMPTY)

    companion object {
        /** Engine description used by the W5 base for granularity validation. */
        val BigQueryEngine: SqlRenderingEngine = cc.monomer.metricflow.domain.sql.render
            .DialectSqlRenderingEngine(name = "BIGQUERY", unsupportedGranularities = emptySet())

        /**
         * Approximate the same continued-fraction reduction Python's `Fraction(d).limit_denominator()`
         * performs. For percentile inputs in [0, 1] with reasonable precision this returns the
         * simplest fraction equal (within ~1e-7) to the input — e.g. 0.5 → (1, 2), 0.1 → (1, 10),
         * 0.75 → (3, 4).
         *
         * The algorithm is the textbook continued-fraction convergents loop with a configurable
         * `maxDenominator` (Python's default is 1_000_000; we use the same).
         */
        /**
         * The maximum denominator Python's `Fraction(...).limit_denominator()` uses by
         * default. Mirrored verbatim so the Kotlin output matches Python.
         */
        internal const val LIMIT_DENOMINATOR_DEFAULT: Int = 1_000_000

        internal fun doubleToLimitedFraction(value: Double, maxDenominator: Int): Pair<Long, Long> {
            require(value.isFinite()) { "Percentile must be a finite number, got $value." }
            require(maxDenominator >= 1) { "maxDenominator must be >= 1" }

            // Handle the easy cases first.
            if (value == 0.0) return 0L to 1L

            // Start from a high-precision fraction representation of `value` and reduce.
            // Use the standard convergents technique.
            var h0 = 0L
            var h1 = 1L
            var k0 = 1L
            var k1 = 0L
            var x = value
            while (true) {
                val a = kotlin.math.floor(x).toLong()
                val h2 = a * h1 + h0
                val k2 = a * k1 + k0
                if (k2 > maxDenominator) break
                h0 = h1
                h1 = h2
                k0 = k1
                k1 = k2
                val frac = x - a
                if (frac == 0.0) break
                x = 1.0 / frac
            }
            // Normalise sign — percentiles in metricflow are non-negative, but be safe.
            return if (h1 < 0 && k1 < 0) (-h1) to (-k1) else h1 to k1
        }
    }
}
