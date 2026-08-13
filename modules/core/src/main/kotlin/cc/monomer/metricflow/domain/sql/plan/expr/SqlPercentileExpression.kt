package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters

/** Type of percentile function used by [SqlPercentileExpression]. */
enum class SqlPercentileFunctionType(val value: String) {
    DISCRETE("discrete"),
    CONTINUOUS("continuous"),
    APPROXIMATE_DISCRETE("approximate_discrete"),
    APPROXIMATE_CONTINUOUS("approximate_continuous"),
}

/**
 * Arguments to a percentile expression — the percentile value (0.0..1.0) and the
 * variant of the percentile function to use.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlPercentileExpressionArgument`.
 */
data class SqlPercentileExpressionArgument(
    val percentile: Double,
    val functionType: SqlPercentileFunctionType,
) {
    companion object {
        /**
         * Derive the percentile arguments from manifest-level aggregation parameters.
         *
         * Port of `SqlPercentileExpressionArgument.from_aggregation_parameters`.
         */
        fun fromAggregationParameters(
            aggParams: MeasureAggregationParameters,
        ): SqlPercentileExpressionArgument {
            val percentile = aggParams.percentile
                ?: throw IllegalStateException(
                    "Percentile value is none - this should have been caught during model parsing.",
                )
            val functionType = when {
                !aggParams.useDiscretePercentile && !aggParams.useApproximatePercentile ->
                    SqlPercentileFunctionType.CONTINUOUS
                aggParams.useDiscretePercentile && !aggParams.useApproximatePercentile ->
                    SqlPercentileFunctionType.DISCRETE
                !aggParams.useDiscretePercentile && aggParams.useApproximatePercentile ->
                    SqlPercentileFunctionType.APPROXIMATE_CONTINUOUS
                else -> SqlPercentileFunctionType.APPROXIMATE_DISCRETE
            }
            return SqlPercentileExpressionArgument(percentile = percentile, functionType = functionType)
        }
    }
}

/**
 * A percentile aggregation, e.g. `percentile_cont(0.1) WITHIN GROUP (ORDER BY col)`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlPercentileExpression`.
 */
class SqlPercentileExpression(
    val orderByArg: SqlExpressionNode,
    val percentileArgs: SqlPercentileExpressionArgument,
) : SqlFunctionExpression(listOf(orderByArg)) {

    override val description: String
        get() = "${percentileArgs.functionType.value} Percentile(${percentileArgs.percentile}) Expression"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_PERCENTILE_ID_PREFIX
    override val requiresParenthesis: Boolean get() = false
    override val isAggregateFunction: Boolean get() = true

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + listOf(
            DisplayedProperty("argument", orderByArg),
            DisplayedProperty("percentile_args", percentileArgs),
        )

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R = visitor.visitPercentileExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode = create(
        orderByArg = orderByArg.rewrite(columnReplacements, shouldRenderTableAlias),
        percentileArgs = percentileArgs,
    )

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage.mergeIterable(
            parentNodes.map { it.lineage } + listOf(SqlExpressionTreeLineage(functionExprs = listOf(this))),
        )

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlPercentileExpression && percentileArgs == other.percentileArgs && parentsMatch(other)

    companion object {
        fun create(
            orderByArg: SqlExpressionNode,
            percentileArgs: SqlPercentileExpressionArgument,
        ): SqlPercentileExpression = SqlPercentileExpression(orderByArg, percentileArgs)
    }
}
