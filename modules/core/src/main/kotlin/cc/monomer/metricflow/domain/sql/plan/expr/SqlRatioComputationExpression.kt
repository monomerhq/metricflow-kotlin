package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Ratio metric expression — divides [numerator] by [denominator] with the appropriate float
 * casts for each engine.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlRatioComputationExpression`. Python notes
 * that this could be decomposed into `SqlCastExpression + SqlMathExpression` in future;
 * for now the only place we do typecasting and division together is ratio metrics, so
 * keeping a dedicated node is simpler.
 */
class SqlRatioComputationExpression(
    val numerator: SqlExpressionNode,
    val denominator: SqlExpressionNode,
) : SqlExpressionNode(listOf(numerator, denominator)) {

    override val description: String get() = "Divide numerator by denominator, with appropriate casting"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_RATIO_COMPUTATION
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitRatioComputationExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        numerator = numerator.rewrite(columnReplacements, shouldRenderTableAlias),
        denominator = denominator.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlRatioComputationExpression && parentsMatch(other)

    companion object {
        fun create(
            numerator: SqlExpressionNode,
            denominator: SqlExpressionNode,
        ): SqlRatioComputationExpression = SqlRatioComputationExpression(numerator, denominator)
    }
}
