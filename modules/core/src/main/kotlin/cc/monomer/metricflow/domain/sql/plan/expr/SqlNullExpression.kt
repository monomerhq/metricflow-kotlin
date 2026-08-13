package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Represents `NULL`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlNullExpression`.
 */
class SqlNullExpression : SqlExpressionNode(emptyList()) {

    override val description: String get() = "NULL Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_NULL_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitNullExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = this

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(otherExprs = listOf(this))

    override fun matches(other: SqlExpressionNode): Boolean = other is SqlNullExpression

    companion object {
        fun create(): SqlNullExpression = SqlNullExpression()
    }
}
