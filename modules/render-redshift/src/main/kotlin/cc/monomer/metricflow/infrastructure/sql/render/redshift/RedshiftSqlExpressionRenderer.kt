package cc.monomer.metricflow.infrastructure.sql.render.redshift

import cc.monomer.metricflow.common.errors.UnsupportedEngineFeatureError
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExtractExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlGenerateUuidExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType
import cc.monomer.metricflow.domain.sql.render.DefaultSqlExpressionRenderer
import cc.monomer.metricflow.domain.sql.render.SqlExpressionRenderResult
import cc.monomer.metricflow.domain.sql.render.SqlRenderingEngine
import cc.monomer.metricflow.domain.sql.render.DialectSqlRenderingEngine

/**
 * Expression renderer for the Redshift engine.
 *
 * Port of `metricflow.sql.render.redshift.RedshiftSqlExpressionRenderer`.
 *
 * Redshift-specific overrides:
 *
 * | Method | Redshift divergence |
 * |---|---|
 * | `doubleDataType` | `DOUBLE PRECISION` |
 * | `supportedPercentileFunctionTypes` | `CONTINUOUS`, `APPROXIMATE_DISCRETE` |
 * | `visitPercentileExpr` | `PERCENTILE_CONT` / `APPROXIMATE PERCENTILE_DISC` |
 * | `renderDatePart` | identity — no ISO-DOW substitution (ANSI default uses `isodow`) |
 * | `visitExtractExpr` | post-processes `DOW` to renormalise 0..6 → 1..7 ISO |
 * | `visitGenerateUuidExpr` | RANDOM-concat hack (Redshift has no native UUID function) |
 */
open class RedshiftSqlExpressionRenderer : DefaultSqlExpressionRenderer() {

    override val sqlEngine: SqlRenderingEngine? get() = RedshiftEngine

    override val doubleDataType: String get() = "DOUBLE PRECISION"

    override val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>
        get() = setOf(
            SqlPercentileFunctionType.CONTINUOUS,
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE,
        )

    override fun visitPercentileExpr(node: SqlPercentileExpression): SqlExpressionRenderResult {
        val argRendered = renderSqlExpr(node.orderByArg)
        val params = argRendered.bindParameterSet
        val percentile = node.percentileArgs.percentile

        val functionStr = when (node.percentileArgs.functionType) {
            SqlPercentileFunctionType.CONTINUOUS -> "PERCENTILE_CONT"
            SqlPercentileFunctionType.DISCRETE -> throw UnsupportedEngineFeatureError(
                "Discrete percentile aggregate not supported for Redshift. Use " +
                    "continuous or approximate discrete percentile in all percentile simple-metrics.",
            )
            SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS -> throw UnsupportedEngineFeatureError(
                "Approximate continuous percentile aggregate not supported for Redshift. Use " +
                    "continuous or approximate discrete percentile in all percentile simple-metrics.",
            )
            SqlPercentileFunctionType.APPROXIMATE_DISCRETE -> "APPROXIMATE PERCENTILE_DISC"
        }

        return SqlExpressionRenderResult(
            sql = "$functionStr($percentile) WITHIN GROUP (ORDER BY (${argRendered.sql}))",
            bindParameterSet = params,
        )
    }

    /**
     * Returns `date_part.value` for every part — Redshift's `EXTRACT(DOW ...)` uses the
     * vanilla part name; the ISO renormalisation happens in [visitExtractExpr] instead.
     */
    override fun renderDatePart(datePart: DatePart): String = datePart.value

    override fun visitExtractExpr(node: SqlExtractExpression): SqlExpressionRenderResult {
        val baseResult = super.visitExtractExpr(node)
        if (node.datePart != DatePart.DOW) return baseResult

        val extractStmt = baseResult.sql
        // Redshift returns 0 (Sunday) - 6 (Saturday); renormalise to ISO 1 (Monday) - 7 (Sunday)
        // with a CASE expression. Note: Python's wording is "0..6 (Monday)" — actually 0=Sunday,
        // 6=Saturday — we just remap 0 → 7 so Sunday becomes the ISO last day.
        val caseExpr = "CASE WHEN $extractStmt = 0 THEN $extractStmt + 7 ELSE $extractStmt END"
        return SqlExpressionRenderResult(sql = caseExpr, bindParameterSet = baseResult.bindParameterSet)
    }

    override fun visitGenerateUuidExpr(
        node: SqlGenerateUuidExpression,
    ): SqlExpressionRenderResult =
        SqlExpressionRenderResult(
            sql = "CONCAT(CAST(RANDOM()*100000000 AS INT)::VARCHAR,CAST(RANDOM()*100000000 AS INT)::VARCHAR)",
            bindParameterSet = SqlBindParameterSet.EMPTY,
        )

    companion object {
        /** Engine description used by the W5 base for granularity validation. */
        val RedshiftEngine: SqlRenderingEngine = DialectSqlRenderingEngine(
            name = "REDSHIFT",
            unsupportedGranularities = emptySet(),
        )
    }
}
