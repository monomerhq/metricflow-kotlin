package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Arithmetic operators used by [SqlArithmeticExpression].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlArithmeticOperator`.
 */
enum class SqlArithmeticOperator(val sql: String) {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/"),
}

/**
 * Binary arithmetic `left <op> right`, e.g. `a + b`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlArithmeticExpression`.
 */
class SqlArithmeticExpression(
    val leftExpr: SqlExpressionNode,
    val operator: SqlArithmeticOperator,
    val rightExpr: SqlExpressionNode,
) : SqlExpressionNode(listOf(leftExpr, rightExpr)) {

    override val description: String get() = "Arithmetic Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_ARITHMETIC_PREFIX
    override val requiresParenthesis: Boolean get() = true

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + listOf(
            DisplayedProperty("left_expr", leftExpr),
            DisplayedProperty("operator", operator.sql),
            DisplayedProperty("right_expr", rightExpr),
        )

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitArithmeticExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        leftExpr = leftExpr.rewrite(columnReplacements, shouldRenderTableAlias),
        operator = operator,
        rightExpr = rightExpr.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlArithmeticExpression && operator == other.operator && parentsMatch(other)

    companion object {
        fun create(
            leftExpr: SqlExpressionNode,
            operator: SqlArithmeticOperator,
            rightExpr: SqlExpressionNode,
        ): SqlArithmeticExpression = SqlArithmeticExpression(leftExpr, operator, rightExpr)
    }
}
