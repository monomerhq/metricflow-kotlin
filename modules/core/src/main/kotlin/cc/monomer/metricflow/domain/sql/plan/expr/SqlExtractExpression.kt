package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart

/**
 * Extract a [DatePart] from a time expression, e.g. `EXTRACT(YEAR FROM ts)`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlExtractExpression`.
 */
class SqlExtractExpression(
    val datePart: DatePart,
    val arg: SqlExpressionNode,
) : SqlExpressionNode(listOf(arg)) {

    override val description: String get() = "Extract ${datePart.name}"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_EXTRACT
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitExtractExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        datePart = datePart,
        arg = arg.rewrite(columnReplacements, shouldRenderTableAlias),
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(otherExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlExtractExpression && datePart == other.datePart && parentsMatch(other)

    companion object {
        fun create(datePart: DatePart, arg: SqlExpressionNode): SqlExtractExpression =
            SqlExtractExpression(datePart, arg)
    }
}
