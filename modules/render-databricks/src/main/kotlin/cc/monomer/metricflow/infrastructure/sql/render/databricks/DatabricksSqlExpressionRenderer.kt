package cc.monomer.metricflow.infrastructure.sql.render.databricks

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import cc.monomer.metricflow.domain.sql.render.DialectSqlRenderingEngine

/**
 * Expression renderer for the Databricks engine.
 *
 * Port of `metricflow.sql.render.databricks.DatabricksSqlExpressionRenderer`.
 *
 * Databricks-specific overrides:
 *
 * | Method | Databricks divergence |
 * |---|---|
 * | `supportedPercentileFunctionTypes` | `CONTINUOUS`, `APPROXIMATE_DISCRETE` |
 * | `renderDatePart` | `DOW` → `DAYOFWEEK_ISO` |
 * | `visitPercentileExpr` | `PERCENTILE_CONT` / `APPROX_PERCENTILE` only |
 */
open class DatabricksSqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = DatabricksEngine

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(
            SqlPercentileFunctionType.CONTINUOUS,
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
        )

    override fun renderDatePart(datePart: DatePart): String =
        if (datePart == DatePart.DOW) "DAYOFWEEK_ISO" else super.renderDatePart(datePart)

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.orderByArg)
        val params = argRendered.bindParameterSet
        val percentile = node.percentileArgs.percentile

        val functionStr = when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.CONTINUOUS -> "PERCENTILE_CONT"
            SqlPercentileFunctionType.DISCRETE -> throw UnsupportedEngineFeatureError(
                "Discrete percentile aggregate not supported for Databricks.  Use " +
                    "continuous or approximate discrete percentile in all percentile simple-metrics.",
            )
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> throw UnsupportedEngineFeatureError(
                "Approximate continuous percentile aggregate not supported for Databricks. Use " +
                    "continuous or approximate discrete percentile in all percentile simple-metrics.",
            )
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE -> return SqlExpressionRenderResult(
                sql = "APPROX_PERCENTILE(${argRendered.sql}, $percentile)",
                bindParameterSet = params,
            )
        }

        return SqlExpressionRenderResult(
            sql = "$functionStr($percentile) WITHIN GROUP (ORDER BY (${argRendered.sql}))",
            bindParameterSet = params,
        )
    }

    companion object {
        /** Engine description used by the W5 base for granularity validation. */
        val DatabricksEngine: SqlRenderingEngine = DialectSqlRenderingEngine(
            name = "DATABRICKS",
            unsupportedGranularities = emptySet(),
        )
    }
}
