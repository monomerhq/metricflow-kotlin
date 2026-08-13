package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * A string literal like `'foo'`. Renderers add the delimiters; [literalValue] is the raw value.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlStringLiteralExpression`.
 */
class SqlStringLiteralExpression(val literalValue: String) : SqlExpressionNode(emptyList()) {

    override val description: String get() = "String Literal: $literalValue"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_STRING_LITERAL_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("value", literalValue)

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitStringLiteralExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = this

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(otherExprs = listOf(this))

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlStringLiteralExpression && literalValue == other.literalValue

    companion object {
        fun create(literalValue: String): SqlStringLiteralExpression =
            SqlStringLiteralExpression(literalValue)
    }
}
