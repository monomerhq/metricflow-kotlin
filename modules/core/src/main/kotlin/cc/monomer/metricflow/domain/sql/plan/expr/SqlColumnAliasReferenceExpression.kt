package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * An unqualified column-alias reference like `SELECT foo` (no `a.foo`).
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlColumnAliasReferenceExpression`. Should
 * generally be avoided because the bare alias can be ambiguous across sources — Python
 * documents the exceptional cases where it's necessary.
 */
class SqlColumnAliasReferenceExpression(val columnAlias: String) : SqlExpressionNode(emptyList()) {

    override val description: String get() = "Unqualified Column: $columnAlias"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_COLUMN_REFERENCE_ID_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("column_alias", columnAlias)

    override val asColumnAliasReferenceExpression: SqlColumnAliasReferenceExpression? get() = this

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitColumnAliasReferenceExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode {
        if (columnReplacements != null) {
            throw NotImplementedError("Column rewrites for alias references are not supported")
        }
        return this
    }

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(columnAliasReferenceExprs = listOf(this))

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlColumnAliasReferenceExpression && columnAlias == other.columnAlias

    companion object {
        fun create(columnAlias: String): SqlColumnAliasReferenceExpression =
            SqlColumnAliasReferenceExpression(columnAlias)
    }
}
