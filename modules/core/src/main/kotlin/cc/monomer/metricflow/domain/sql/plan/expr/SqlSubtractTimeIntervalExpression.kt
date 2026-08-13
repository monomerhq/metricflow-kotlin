package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Subtract `count` periods of [granularity] from a timestamp expression.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlSubtractTimeIntervalExpression`.
 */
class SqlSubtractTimeIntervalExpression(
    val arg: SqlExpressionNode,
    val count: Int,
    val granularity: TimeGranularity,
) : SqlExpressionNode(listOf(arg)) {

    override val description: String get() = "Subtract time interval"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_SUBTRACT_TIME_INTERVAL_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitSubtractTimeIntervalExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        arg = arg.rewrite(columnReplacements, shouldRenderTableAlias),
        count = count,
        granularity = granularity,
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlSubtractTimeIntervalExpression &&
            count == other.count &&
            granularity == other.granularity &&
            parentsMatch(other)

    companion object {
        fun create(
            arg: SqlExpressionNode,
            count: Int,
            granularity: TimeGranularity,
        ): SqlSubtractTimeIntervalExpression =
            SqlSubtractTimeIntervalExpression(arg, count, granularity)
    }
}
