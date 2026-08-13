package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.expr.SqlPercentileFunctionType

/**
 * Renders [SqlExpressionNode]s into SQL string fragments.
 *
 * Port of `metricflow.sql.render.expr_renderer.SqlExpressionRenderer`. The interface is a
 * specialised [SqlExpressionNodeVisitor] returning [SqlExpressionRenderResult] — every
 * dialect-specific renderer (W6) implements (or extends [DefaultSqlExpressionRenderer]) and
 * overrides the variants where the dialect's SQL diverges from ANSI.
 *
 * The two convenience methods ([renderSqlExpr] and [renderGroupByExpr]) accept higher-level
 * objects and dispatch to the appropriate visitor method — the Python equivalents are
 * `render_sql_expr` and `render_group_by_expr`.
 */
interface SqlExpressionRenderer : SqlExpressionNodeVisitor<SqlExpressionRenderResult> {

    /** Render the given expression to a SQL fragment. */
    fun renderSqlExpr(sqlExpr: SqlExpressionNode): SqlExpressionRenderResult = sqlExpr.accept(this)

    /**
     * Render the expression of a `GROUP BY` column.
     *
     * Most engines render the same as a regular SELECT column; some (e.g. Trino) prefer
     * column aliases — those override this method.
     */
    fun renderGroupByExpr(groupByColumn: SqlSelectColumn): SqlExpressionRenderResult =
        renderSqlExpr(groupByColumn.expr)

    /** SQL type name to cast double-precision columns to, e.g. `DOUBLE`. */
    val doubleDataType: String

    /** SQL type name to cast timestamp columns to, e.g. `TIMESTAMP`. */
    val timestampDataType: String

    /** The percentile function types this renderer supports. */
    val supportedPercentileFunctionTypes: Collection<SqlPercentileFunctionType>

    /** Returns true if this renderer can emit the given percentile function variant. */
    fun canRenderPercentileFunction(percentileType: SqlPercentileFunctionType): Boolean =
        percentileType in supportedPercentileFunctionTypes
}
