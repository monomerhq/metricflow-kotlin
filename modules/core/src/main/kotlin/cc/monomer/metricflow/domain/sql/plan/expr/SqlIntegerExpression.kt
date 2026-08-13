package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * An integer literal like `1`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlIntegerExpression`.
 */
class SqlIntegerExpression(val integerValue: Int) : SqlExpressionNode(emptyList()) {

    override val description: String get() = "Integer: $integerValue"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_INTEGER_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("value", integerValue)

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitIntegerExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = this

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(otherExprs = listOf(this))

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlIntegerExpression && integerValue == other.integerValue

    companion object {
        fun create(integerValue: Int): SqlIntegerExpression = SqlIntegerExpression(integerValue)
    }
}
