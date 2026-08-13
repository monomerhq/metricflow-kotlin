package cc.monomer.metricflow.infrastructure.sql.render.duckdb

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.expr.SqlAddTimeExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlArithmeticOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIntegerExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import cc.monomer.metricflow.domain.sql.render.DialectSqlRenderingEngine

/**
 * Expression renderer for the DuckDB engine.
 *
 * Port of `metricflow.sql.render.duckdb_renderer.DuckDbSqlExpressionRenderer`.
 *
 * DuckDB-specific overrides:
 *
 * | Method | DuckDB divergence |
 * |---|---|
 * | `supportedPercentileFunctionTypes` | `CONTINUOUS`, `DISCRETE`, `APPROXIMATE_CONTINUOUS` |
 * | `visitSubtractTimeIntervalExpr` | `arg - INTERVAL count gran` |
 * | `visitAddTimeExpr` | `arg + INTERVAL count gran` |
 * | `visitGenerateUuidExpr` | `GEN_RANDOM_UUID()` |
 * | `visitPercentileExpr` | `PERCENTILE_CONT` / `PERCENTILE_DISC` / `approx_quantile` |
 */
open class DuckDbSqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = DuckDbEngine

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(
            SqlPercentileFunctionType.CONTINUOUS,
            SqlPercentileFunctionType.DISCRETE,
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS,
        )

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
            sql = "${argRendered.sql} - INTERVAL $count ${granularity.value}",
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
            sql = "${argRendered.sql} + INTERVAL $countSql ${granularity.value}",
            bindParameterSet = Mergeable.mergeIterable(
                listOf(argRendered.bindParameterSet, countRendered.bindParameterSet),
                SqlBindParameterSet.EMPTY,
            ),
        )
    }

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(sql = "GEN_RANDOM_UUID()", bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.orderByArg)
        val params = argRendered.bindParameterSet
        val percentile = node.percentileArgs.percentile

        val functionStr = when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.CONTINUOUS -> "PERCENTILE_CONT"
            SqlPercentileFunctionType.DISCRETE -> "PERCENTILE_DISC"
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> return SqlExpressionRenderResult(
                sql = "approx_quantile(${argRendered.sql}, $percentile)",
                bindParameterSet = params,
            )
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE -> throw UnsupportedEngineFeatureError(
                "Approximate discrete percentile aggregate not supported for DuckDB. Set " +
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
        val DuckDbEngine: SqlRenderingEngine = DialectSqlRenderingEngine(
            name = "DUCKDB",
            unsupportedGranularities = emptySet(),
        )
    }
}
