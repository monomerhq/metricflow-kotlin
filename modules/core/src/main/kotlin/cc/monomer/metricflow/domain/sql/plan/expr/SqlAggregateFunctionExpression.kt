package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType

/**
 * An aggregate function applied to its arguments, e.g. `SUM(1)`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlAggregateFunctionExpression`.
 */
class SqlAggregateFunctionExpression(
    val sqlFunction: SqlFunction,
    val sqlFunctionArgs: List<SqlExpressionNode>,
) : SqlFunctionExpression(sqlFunctionArgs) {

    override val description: String get() = "${sqlFunction.sql} Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_FUNCTION_ID_PREFIX
    override val requiresParenthesis: Boolean get() = false
    override val isAggregateFunction: Boolean get() = true

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("function", sqlFunction) +
            sqlFunctionArgs.map { DisplayedProperty("argument", it) }

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitFunctionExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        sqlFunction = sqlFunction,
        sqlFunctionArgs = sqlFunctionArgs.map { it.rewrite(columnReplacements, shouldRenderTableAlias) },
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(functionExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlAggregateFunctionExpression && sqlFunction == other.sqlFunction && parentsMatch(other)

    companion object {
        fun create(
            sqlFunction: SqlFunction,
            sqlFunctionArgs: List<SqlExpressionNode>,
        ): SqlAggregateFunctionExpression = SqlAggregateFunctionExpression(sqlFunction, sqlFunctionArgs)

        /** Build an aggregate expression for the given [aggregationType] over [sqlColumnExpression]. */
        fun fromAggregationType(
            aggregationType: AggregationType,
            sqlColumnExpression: SqlColumnReferenceExpression,
        ): SqlAggregateFunctionExpression = create(
            sqlFunction = SqlFunction.fromAggregationType(aggregationType),
            sqlFunctionArgs = listOf(sqlColumnExpression),
        )
    }
}
