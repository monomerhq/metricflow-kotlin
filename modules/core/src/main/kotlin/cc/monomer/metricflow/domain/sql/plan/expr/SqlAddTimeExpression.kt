package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Add `countExpr` periods of [granularity] to a timestamp expression.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlAddTimeExpression`.
 */
class SqlAddTimeExpression(
    val arg: SqlExpressionNode,
    val countExpr: SqlExpressionNode,
    val granularity: TimeGranularity,
) : SqlExpressionNode(listOf(arg, countExpr)) {

    override val description: String get() = "Add time interval"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_ADD_TIME_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitAddTimeExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        arg = arg.rewrite(columnReplacements, shouldRenderTableAlias),
        countExpr = countExpr.rewrite(columnReplacements, shouldRenderTableAlias),
        granularity = granularity,
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlAddTimeExpression &&
            countExpr == other.countExpr &&
            granularity == other.granularity &&
            arg == other.arg

    companion object {
        fun create(
            arg: SqlExpressionNode,
            countExpr: SqlExpressionNode,
            granularity: TimeGranularity,
        ): SqlAddTimeExpression = SqlAddTimeExpression(arg, countExpr, granularity)
    }
}
