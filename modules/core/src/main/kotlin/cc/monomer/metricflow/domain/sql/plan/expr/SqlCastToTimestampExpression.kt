package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Cast an expression to the timestamp type, e.g. `CAST('2020-01-01' AS TIMESTAMP)`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlCastToTimestampExpression`.
 */
class SqlCastToTimestampExpression(val arg: SqlExpressionNode) : SqlExpressionNode(listOf(arg)) {

    override val description: String get() = "Cast to Timestamp"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_CAST_TO_TIMESTAMP_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitCastToTimestampExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(arg.rewrite(columnReplacements, shouldRenderTableAlias))

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlCastToTimestampExpression && parentsMatch(other)

    companion object {
        fun create(arg: SqlExpressionNode): SqlCastToTimestampExpression =
            SqlCastToTimestampExpression(arg)
    }
}
