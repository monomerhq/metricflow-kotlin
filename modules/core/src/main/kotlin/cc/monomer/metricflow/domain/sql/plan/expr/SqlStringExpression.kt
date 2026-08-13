package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * A raw SQL fragment in string form — opaque to the optimizer (no structural information).
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlStringExpression`.
 *
 * When [usedColumns] is non-null it must list **all** columns referenced inside [sqlExpr];
 * incomplete `used_columns` lists are documented in Python as a source of bugs.
 */
class SqlStringExpression(
    val sqlExpr: String,
    override val bindParameterSet: SqlBindParameterSet,
    override val requiresParenthesis: Boolean,
    val usedColumns: List<String>?,
) : SqlExpressionNode(emptyList()) {

    override val description: String get() = "String SQL Expression: $sqlExpr"

    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_STRING_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("sql_expr", sqlExpr)

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitStringExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode {
        if (columnReplacements != null) {
            throw NotImplementedError("Column rewrites on opaque string expressions are not supported")
        }
        return this
    }

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(stringExprs = listOf(this))

    override val asStringExpression: SqlStringExpression? get() = this

    override fun matches(other: SqlExpressionNode): Boolean {
        if (other !is SqlStringExpression) return false
        return sqlExpr == other.sqlExpr &&
            usedColumns == other.usedColumns &&
            bindParameterSet == other.bindParameterSet
    }

    companion object {
        /** Convenience matching Python's `SqlStringExpression.create`. */
        fun create(
            sqlExpr: String,
            bindParameterSet: SqlBindParameterSet,
            requiresParenthesis: Boolean,
            usedColumns: List<String>?,
        ): SqlStringExpression = SqlStringExpression(
            sqlExpr = sqlExpr,
            bindParameterSet = bindParameterSet,
            requiresParenthesis = requiresParenthesis,
            usedColumns = usedColumns,
        )
    }
}
