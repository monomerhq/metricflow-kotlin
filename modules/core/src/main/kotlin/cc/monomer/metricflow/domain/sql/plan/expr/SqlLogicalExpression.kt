package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Binary logical operator used by [SqlLogicalExpression].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlLogicalOperator`.
 */
enum class SqlLogicalOperator(val sql: String) {
    AND("AND"),
    OR("OR"),
}

/**
 * An N-ary logical chain like `a AND b AND c`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlLogicalExpression`.
 */
class SqlLogicalExpression(
    val operator: SqlLogicalOperator,
    val args: List<SqlExpressionNode>,
) : SqlExpressionNode(args) {

    override val description: String get() = "Logical Operator ${operator.sql}"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_LOGICAL_OPERATOR_PREFIX
    override val requiresParenthesis: Boolean get() = true

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitLogicalExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        operator = operator,
        args = args.map { it.rewrite(columnReplacements, shouldRenderTableAlias) },
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlLogicalExpression && operator == other.operator && parentsMatch(other)

    companion object {
        fun create(operator: SqlLogicalOperator, args: List<SqlExpressionNode>): SqlLogicalExpression =
            SqlLogicalExpression(operator, args)
    }
}
