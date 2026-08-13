package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * The `expr IS NULL` predicate.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlIsNullExpression`.
 */
class SqlIsNullExpression(val arg: SqlExpressionNode) : SqlExpressionNode(listOf(arg)) {

    override val description: String get() = "IS NULL Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_IS_NULL_PREFIX
    override val requiresParenthesis: Boolean get() = true

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitIsNullExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(arg.rewrite(columnReplacements, shouldRenderTableAlias))

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            listOf(arg.lineage, SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlIsNullExpression && parentsMatch(other)

    companion object {
        fun create(arg: SqlExpressionNode): SqlIsNullExpression = SqlIsNullExpression(arg)
    }
}
