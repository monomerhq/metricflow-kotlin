package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType

/**
 * Names of known SQL functions like `SUM()`. Values are the SQL identifiers used in rendering.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlFunction`.
 *
 * Note: `COUNT_DISTINCT` renders as `COUNT` because most engines implement it via the
 * `DISTINCT` keyword (e.g. `COUNT(DISTINCT col)`), not as a separate function name. The
 * renderer expands it to the right shape.
 */
enum class SqlFunction(val sql: String) {
    // Aggregation functions
    AVERAGE("AVG"),
    // Most engines implement count_distinct as a leading DISTINCT keyword like `COUNT(DISTINCT col1, col2...)`.
    COUNT_DISTINCT("COUNT"),
    MAX("MAX"),
    MIN("MIN"),
    SUM("SUM"),

    // Field-management functions
    COALESCE("COALESCE"),
    CONCAT("CONCAT");

    companion object {
        /** The aggregation functions that imply `DISTINCT` semantics. */
        val DISTINCT_AGGREGATION_FUNCTIONS: List<SqlFunction> = listOf(COUNT_DISTINCT)

        /** Returns true when [functionType] aggregates with `DISTINCT` semantics. */
        fun isDistinctAggregation(functionType: SqlFunction): Boolean =
            functionType in DISTINCT_AGGREGATION_FUNCTIONS

        /** Whether the given function is an aggregation function (vs. field-management). */
        fun isAggregation(functionType: SqlFunction): Boolean =
            functionType == AVERAGE ||
                functionType == COUNT_DISTINCT ||
                functionType == MAX ||
                functionType == MIN ||
                functionType == SUM

        /**
         * Map an [AggregationType] (from manifest) onto a [SqlFunction] (for rendering).
         *
         * Port of `SqlFunction.from_aggregation_type`. The Python version's `else:
         * assert_values_exhausted(...)` reflex is encoded by Kotlin's exhaustive `when`.
         */
        fun fromAggregationType(aggregationType: AggregationType): SqlFunction = when (aggregationType) {
            AggregationType.AVERAGE -> AVERAGE
            AggregationType.COUNT_DISTINCT -> COUNT_DISTINCT
            AggregationType.MAX -> MAX
            AggregationType.MIN -> MIN
            AggregationType.SUM -> SUM
            AggregationType.PERCENTILE -> throw IllegalArgumentException(
                "Unhandled aggregation type $aggregationType - this should have been handled in percentile aggregation node.",
            )
            AggregationType.MEDIAN -> throw IllegalArgumentException(
                "Unhandled aggregation type $aggregationType - this should have been transformed to PERCENTILE during model parsing.",
            )
            AggregationType.SUM_BOOLEAN, AggregationType.COUNT -> throw IllegalArgumentException(
                "Unhandled aggregation type $aggregationType - this should have been transformed to SUM during model parsing.",
            )
        }
    }
}

/**
 * Open base for every function-expression variant — port of
 * `metricflow_semantics.sql.sql_exprs.SqlFunctionExpression`.
 *
 * The Python parent class has an abstract `is_aggregate_function` and a small factory
 * `build_expression_from_aggregation_type`; we restate both.
 */
abstract class SqlFunctionExpression(parentNodes: List<SqlExpressionNode>) :
    SqlExpressionNode(parentNodes) {

    /** Whether this is an aggregate (as opposed to e.g. a window or field-management) function. */
    abstract val isAggregateFunction: Boolean

    companion object {
        /**
         * Factory matching Python's `SqlFunctionExpression.build_expression_from_aggregation_type`.
         *
         * Returns a [SqlPercentileExpression] for [AggregationType.PERCENTILE]; otherwise a
         * [SqlAggregateFunctionExpression].
         */
        fun buildExpressionFromAggregationType(
            aggregationType: AggregationType,
            sqlColumnExpression: SqlColumnReferenceExpression,
            aggParams: MeasureAggregationParameters?,
        ): SqlFunctionExpression =
            if (aggregationType == AggregationType.PERCENTILE) {
                checkNotNull(aggParams) { "Agg_params is none, which should have been caught in validation" }
                SqlPercentileExpression.create(
                    orderByArg = sqlColumnExpression,
                    percentileArgs = SqlPercentileExpressionArgument.fromAggregationParameters(aggParams),
                )
            } else {
                SqlAggregateFunctionExpression.fromAggregationType(aggregationType, sqlColumnExpression)
            }
    }
}
