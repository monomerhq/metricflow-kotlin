package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.enums.PeriodAggregation

/**
 * Names of supported window functions.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlWindowFunction`.
 */
enum class SqlWindowFunction(val sql: String) {
    FIRST_VALUE("FIRST_VALUE"),
    LAST_VALUE("LAST_VALUE"),
    AVERAGE("AVG"),
    ROW_NUMBER("ROW_NUMBER"),
    LEAD("LEAD");

    /** Whether ordering affects the result of this window function. */
    val requiresOrdering: Boolean
        get() = when (this) {
            FIRST_VALUE, LAST_VALUE, ROW_NUMBER, LEAD -> true
            AVERAGE -> false
        }

    /** Whether this window function allows a `ROWS BETWEEN ...` frame clause. */
    val allowsFrameClause: Boolean
        get() = when (this) {
            FIRST_VALUE, LAST_VALUE, AVERAGE -> true
            ROW_NUMBER, LEAD -> false
        }

    companion object {
        /** Pick the window function appropriate for a given [PeriodAggregation]. */
        fun forPeriodAgg(periodAgg: PeriodAggregation): SqlWindowFunction = when (periodAgg) {
            PeriodAggregation.FIRST -> FIRST_VALUE
            PeriodAggregation.LAST -> LAST_VALUE
            PeriodAggregation.AVERAGE -> AVERAGE
        }
    }
}

/**
 * One ORDER BY argument inside a window function — `expr [DESC|ASC] [NULLS LAST|NULLS FIRST]`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlWindowOrderByArgument`.
 */
data class SqlWindowOrderByArgument(
    val expr: SqlExpressionNode,
    val descending: Boolean?,
    val nullsLast: Boolean?,
) {
    /** The suffix string ` DESC NULLS LAST` etc., to be appended to the rendered [expr]. */
    val suffix: String
        get() = buildList {
            if (descending != null) add(if (descending) "DESC" else "ASC")
            if (nullsLast != null) add(if (nullsLast) "NULLS LAST" else "NULLS FIRST")
        }.joinToString(" ")
}

/**
 * A window function expression — `SUM(foo) OVER (PARTITION BY x ORDER BY y)`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlWindowFunctionExpression`.
 */
class SqlWindowFunctionExpression(
    val sqlFunction: SqlWindowFunction,
    val sqlFunctionArgs: List<SqlExpressionNode>,
    val partitionByArgs: List<SqlExpressionNode>,
    val orderByArgs: List<SqlWindowOrderByArgument>,
) : SqlFunctionExpression(
    parentNodes = sqlFunctionArgs + partitionByArgs + orderByArgs.map { it.expr },
) {

    override val description: String get() = "${sqlFunction.sql} Window Function Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_WINDOW_FUNCTION_ID_PREFIX
    override val requiresParenthesis: Boolean get() = false
    override val isAggregateFunction: Boolean get() = false
    override val isVerbose: Boolean get() = true

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            DisplayedProperty("function", sqlFunction) +
            sqlFunctionArgs.map { DisplayedProperty("argument", it) } +
            partitionByArgs.map { DisplayedProperty("partition_by_argument", it) } +
            orderByArgs.map { DisplayedProperty("order_by_argument", it) }

    override val asWindowFunctionExpression: SqlWindowFunctionExpression? get() = this

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitWindowFunctionExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        sqlFunction = sqlFunction,
        sqlFunctionArgs = sqlFunctionArgs.map { it.rewrite(columnReplacements, shouldRenderTableAlias) },
        partitionByArgs = partitionByArgs.map { it.rewrite(columnReplacements, shouldRenderTableAlias) },
        orderByArgs = orderByArgs.map {
            SqlWindowOrderByArgument(
                expr = it.expr.rewrite(columnReplacements, shouldRenderTableAlias),
                descending = it.descending,
                nullsLast = it.nullsLast,
            )
        },
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(functionExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean {
        if (other !is SqlWindowFunctionExpression) return false
        return sqlFunction == other.sqlFunction &&
            orderByArgs == other.orderByArgs &&
            partitionByArgs == other.partitionByArgs &&
            sqlFunctionArgs == other.sqlFunctionArgs
    }

    companion object {
        fun create(
            sqlFunction: SqlWindowFunction,
            sqlFunctionArgs: List<SqlExpressionNode>,
            partitionByArgs: List<SqlExpressionNode>,
            orderByArgs: List<SqlWindowOrderByArgument>,
        ): SqlWindowFunctionExpression =
            SqlWindowFunctionExpression(sqlFunction, sqlFunctionArgs, partitionByArgs, orderByArgs)
    }
}
