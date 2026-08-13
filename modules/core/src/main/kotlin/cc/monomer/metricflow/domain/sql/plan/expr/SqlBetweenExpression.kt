package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * `column BETWEEN start AND end`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlBetweenExpression`.
 */
class SqlBetweenExpression(
    val columnArg: SqlExpressionNode,
    val startExpr: SqlExpressionNode,
    val endExpr: SqlExpressionNode,
) : SqlExpressionNode(listOf(columnArg, startExpr, endExpr)) {

    override val description: String get() = "BETWEEN operator"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_BETWEEN_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitBetweenExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        columnArg = columnArg.rewrite(columnReplacements, shouldRenderTableAlias),
        startExpr = startExpr.rewrite(columnReplacements, shouldRenderTableAlias),
        endExpr = endExpr.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlBetweenExpression && parentsMatch(other)

    companion object {
        fun create(
            columnArg: SqlExpressionNode,
            startExpr: SqlExpressionNode,
            endExpr: SqlExpressionNode,
        ): SqlBetweenExpression = SqlBetweenExpression(columnArg, startExpr, endExpr)
    }
}
