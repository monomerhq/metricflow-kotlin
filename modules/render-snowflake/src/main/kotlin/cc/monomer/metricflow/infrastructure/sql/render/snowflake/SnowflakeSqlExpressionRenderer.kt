package cc.monomer.metricflow.infrastructure.sql.render.snowflake

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
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
