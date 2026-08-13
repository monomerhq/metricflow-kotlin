package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * `DATE_TRUNC` an expression to a [TimeGranularity].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlDateTruncExpression`.
 */
class SqlDateTruncExpression(
    val timeGranularity: TimeGranularity,
    val arg: SqlExpressionNode,
) : SqlExpressionNode(listOf(arg)) {

    override val description: String get() = "DATE_TRUNC() to $timeGranularity"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_DATE_TRUNC
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitDateTruncExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        timeGranularity = timeGranularity,
        arg = arg.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlDateTruncExpression && timeGranularity == other.timeGranularity && parentsMatch(other)

    companion object {
        fun create(timeGranularity: TimeGranularity, arg: SqlExpressionNode): SqlDateTruncExpression =
            SqlDateTruncExpression(timeGranularity, arg)
    }
}
