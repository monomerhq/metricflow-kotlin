package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Renders a SQL function call that generates a random UUID (non-deterministic).
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlGenerateUuidExpression`.
 */
class SqlGenerateUuidExpression : SqlExpressionNode(emptyList()) {

    override val description: String get() = "Generate a universally unique identifier"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_GENERATE_UUID_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitGenerateUuidExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = this

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(otherExprs = listOf(this))

    // Python returns false for matches() on UUID — every UUID expression is semantically
    // distinct because it produces a different value at runtime.
    override fun matches(other: SqlExpressionNode): Boolean = false

    companion object {
        fun create(): SqlGenerateUuidExpression = SqlGenerateUuidExpression()
    }
}
