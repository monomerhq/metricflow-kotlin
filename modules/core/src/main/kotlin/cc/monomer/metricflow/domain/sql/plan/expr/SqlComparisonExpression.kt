package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Comparison operators used by [SqlComparisonExpression].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlComparison`. Values are the literal
 * operator strings used when rendering.
 */
enum class SqlComparison(val sql: String) {
    LESS_THAN("<"),
    GREATER_THAN(">"),
    LESS_THAN_OR_EQUALS("<="),
    GREATER_THAN_OR_EQUALS(">="),
    EQUALS("="),
}

/**
 * Binary comparison `left <op> right`, e.g. `my_table.my_column = a + b`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlComparisonExpression`.
 */
class SqlComparisonExpression(
    val leftExpr: SqlExpressionNode,
    val comparison: SqlComparison,
    val rightExpr: SqlExpressionNode,
) : SqlExpressionNode(listOf(leftExpr, rightExpr)) {

    override val description: String get() = "${comparison.sql} Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_COMPARISON_ID_PREFIX
    override val requiresParenthesis: Boolean get() = true

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + listOf(
            DisplayedProperty("left_expr", leftExpr),
            DisplayedProperty("comparison", comparison.sql),
            DisplayedProperty("right_expr", rightExpr),
        )

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitComparisonExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        leftExpr = leftExpr.rewrite(columnReplacements, shouldRenderTableAlias),
        comparison = comparison,
        rightExpr = rightExpr.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlComparisonExpression && comparison == other.comparison && parentsMatch(other)

    companion object {
        fun create(
            leftExpr: SqlExpressionNode,
            comparison: SqlComparison,
            rightExpr: SqlExpressionNode,
        ): SqlComparisonExpression = SqlComparisonExpression(leftExpr, comparison, rightExpr)
    }
}
