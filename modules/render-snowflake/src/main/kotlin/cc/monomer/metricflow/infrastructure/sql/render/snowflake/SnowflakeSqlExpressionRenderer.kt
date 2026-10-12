package cc.monomer.metricflow.infrastructure.sql.render.snowflake

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import cc.monomer.metricflow.domain.sql.render.DialectSqlRenderingEngine

/**
 * Expression renderer for the Snowflake engine.
 *
 * Port of `metricflow.sql.render.snowflake.SnowflakeSqlExpressionRenderer`.
 *
 * Snowflake-specific overrides:
 *
 * | Method | Snowflake divergence |
 * |---|---|
 * | `supportedPercentileFunctionTypes` | `CONTINUOUS`, `DISCRETE`, `APPROXIMATE_CONTINUOUS` |
 * | `visitDateTruncExpr` | `WEEK` → ISO Monday from `DAYOFWEEKISO`, independent of `WEEK_START` |
 * | `renderDatePart` | `DOW` → `dayofweekiso` |
 * | `visitGenerateUuidExpr` | `UUID_STRING()` |
 * | `visitPercentileExpr` | full `PERCENTILE_CONT`/`PERCENTILE_DISC`/`APPROX_PERCENTILE` support |
 */
open class SnowflakeSqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = SnowflakeEngine

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(
            SqlPercentileFunctionType.CONTINUOUS,
            SqlPercentileFunctionType.DISCRETE,
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
        )

    /**
     * Truncate to a MetricFlow week, which starts on Monday (ISO 8601) on every engine.
     *
     * Snowflake's `DATE_TRUNC('week', x)` starts the week on the account or session `WEEK_START`
     * day, so its buckets change with warehouse settings. `DAYOFWEEKISO` ignores `WEEK_START` and
     * `WEEK_OF_YEAR_POLICY`, so the Monday is `DATE_TRUNC('day', x)` moved back by
     * `DAYOFWEEKISO(x) - 1` days. `DATE_TRUNC` returns the input type and `DATEADD` with a `day`
     * part keeps a DATE as a DATE and a timestamp as a timestamp, so no cast is introduced.
     * Every other granularity uses the default `DATE_TRUNC` rendering.
     */
    override fun visitDateTruncExpr(node: SqlDateTruncExpression): SqlExpressionRenderResult {
        if (node.timeGranularity != TimeGranularity.WEEK) return super.visitDateTruncExpr(node)
        validateGranularityForEngine(node.timeGranularity)
        val argRendered = renderSqlExpr(node.arg)
        val argSql = argRendered.sql
        return SqlExpressionRenderResult(
            sql = "DATEADD(day, 1 - DAYOFWEEKISO($argSql), DATE_TRUNC('day', $argSql))",
            bindParameterSet = argRendered.bindParameterSet,
        )
    }

    override fun renderDatePart(datePart: DatePart): String =
        if (datePart == DatePart.DOW) "dayofweekiso" else super.renderDatePart(datePart)

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "UUID_STRING()", bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.orderByArg)
        val params = argRendered.bindParameterSet
        val percentile = node.percentileArgs.percentile

        val functionStr = when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.CONTINUOUS -> "PERCENTILE_CONT"
            SqlPercentileFunctionType.DISCRETE -> "PERCENTILE_DISC"
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> return SqlExpressionRenderResult(
                sql = "APPROX_PERCENTILE(${argRendered.sql}, $percentile)",
                bindParameterSet = params,
            )
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE -> throw UnsupportedEngineFeatureError(
                "Approximate discrete percentile aggregate not supported for Snowflake. Set " +
                    "use_discrete_percentile and/or use_approximate_percentile to false in all percentile simple-metrics.",
            )
        }

        return SqlExpressionRenderResult(
            sql = "$functionStr($percentile) WITHIN GROUP (ORDER BY (${argRendered.sql}))",
            bindParameterSet = params,
        )
    }

    companion object {
        /** Engine description used by the W5 base for granularity validation. */
        val SnowflakeEngine: SqlRenderingEngine = DialectSqlRenderingEngine(
            name = "SNOWFLAKE",
            unsupportedGranularities = emptySet(),
        )
    }
}
