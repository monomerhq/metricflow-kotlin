package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * A `CASE WHEN ... THEN ... [ELSE ...] END` expression.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlCaseExpression`. The map is ordered
 * (insertion order) — `LinkedHashMap` preserves Python's `dict` semantics.
 */
class SqlCaseExpression(
    val whenToThenExprs: Map<SqlExpressionNode, SqlExpressionNode>,
    val elseExpr: SqlExpressionNode?,
) : SqlExpressionNode(
    parentNodes = buildList {
        for ((whenExpr, thenExpr) in whenToThenExprs) {
            add(whenExpr)
            add(thenExpr)
        }
        if (elseExpr != null) add(elseExpr)
    },
) {

    override val description: String get() = "Case expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_CASE_PREFIX
    override val requiresParenthesis: Boolean get() = false
    override val isVerbose: Boolean get() = true

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitCaseExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode {
        val rewritten = LinkedHashMap<SqlExpressionNode, SqlExpressionNode>()
        for ((whenExpr, thenExpr) in whenToThenExprs) {
            rewritten[whenExpr.rewrite(columnReplacements, shouldRenderTableAlias)] =
                thenExpr.rewrite(columnReplacements, shouldRenderTableAlias)
        }
        return create(
            whenToThenExprs = rewritten,
            elseExpr = elseExpr?.rewrite(columnReplacements, shouldRenderTableAlias),
        )
    }

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlCaseExpression &&
            whenToThenExprs == other.whenToThenExprs &&
            elseExpr == other.elseExpr

    companion object {
        fun create(
            whenToThenExprs: Map<SqlExpressionNode, SqlExpressionNode>,
            elseExpr: SqlExpressionNode?,
        ): SqlCaseExpression = SqlCaseExpression(whenToThenExprs, elseExpr)
    }
}
