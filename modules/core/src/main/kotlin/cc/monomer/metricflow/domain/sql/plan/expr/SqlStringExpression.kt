package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * A SQL scalar fragment kept in string form for dialect rendering. The optimizer still treats
 * it as opaque, but construction validates it with [SqlScalarExpressionParser] so it cannot
 * carry a query, relation-producing expression, or trailing statement into the plan.
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

    init {
        // Keep the original text for dialect rendering, but never allow an opaque query or
        // trailing statement to enter a semantic SQL plan.
        referencedColumnNames(sqlExpr)
    }

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
        /**
         * Validate a SQL fragment as one scalar expression and return its referenced columns.
         *
         * This is the public boundary for callers that need the same maintained syntax and
         * shape validation as [SqlStringExpression] construction. The parser implementation
         * remains internal so callers cannot accidentally depend on its representation.
         */
        fun referencedColumnNames(sqlExpr: String): Set<String> =
            SqlScalarExpressionParser.referencedColumnNames(sqlExpr)

        /** Convenience matching Python's `SqlStringExpression.create`. */
        fun create(
            sqlExpr: String,
            bindParameterSet: SqlBindParameterSet,
            requiresParenthesis: Boolean,
            usedColumns: List<String>?,
        ): SqlStringExpression {
            return SqlStringExpression(
                sqlExpr = sqlExpr,
                bindParameterSet = bindParameterSet,
                requiresParenthesis = requiresParenthesis,
                usedColumns = usedColumns,
            )
        }
    }
}
