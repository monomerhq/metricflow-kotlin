package cc.monomer.metricflow.domain.plan_conversion.helpers

import cc.monomer.metricflow.common.errors.errorIfNotStandardGrain
import cc.monomer.metricflow.domain.dataflow.dataset.AnnotatedSqlDataSet
import cc.monomer.metricflow.domain.dataflow.nodes.JoinConversionEventsNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToTimeSpineNode
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparison
import cc.monomer.metricflow.domain.sql.plan.expr.SqlComparisonExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlDateTruncExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlIsNullExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalOperator
import cc.monomer.metricflow.domain.sql.plan.expr.SqlSubtractTimeIntervalExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode

/**
 * Helper describing two columns that should be equal between sources in a join.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.sql_join_builder.ColumnEqualityDescription`.
 *
 * The [treatNullsAsEqual] toggle determines what to do with null-valued inputs. SQL normally
 * returns NULL for any equality comparison with a NULL on either side (so `NULL = NULL` is
 * NULL, which evaluates to false in a join condition). When [treatNullsAsEqual] is true the
 * comparison is rendered as:
 * ```
 * (left = right OR (left IS NULL AND right IS NULL))
 * ```
 */
data class ColumnEqualityDescription(
    val leftColumnAlias: String,
    val rightColumnAlias: String,
    val treatNullsAsEqual: Boolean,
)

/**
 * Helper class for constructing various JOIN clauses inside a [SqlSelectStatementNode].
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.sql_join_builder.SqlPlanJoinBuilder`. Every
 * method is a stateless static helper — the equivalent of Python's `@staticmethod` cluster.
 */
object SqlPlanJoinBuilder {

    /**
     * Make a join description where the base condition is a set of equality comparisons
     * between columns.
     *
     * Typically the columns in [columnEqualityDescriptions] are entities being matched, but
     * they may also include dimension partitions or time-dimension columns where equality is
     * expected.
     *
     * The framework today only supports condition-bearing joins or `CROSS JOIN`; an empty
     * [columnEqualityDescriptions] list combined with any non-cross [joinType] is an
     * assertion error.
     */
    fun makeColumnEqualitySqlJoinDescription(
        rightSourceNode: SqlSelectStatementNode,
        leftSourceAlias: String,
        rightSourceAlias: String,
        columnEqualityDescriptions: List<ColumnEqualityDescription>,
        joinType: SqlJoinType,
        additionalOnConditions: List<SqlExpressionNode>,
    ): SqlJoinDescription {
        check(columnEqualityDescriptions.isNotEmpty() || joinType == SqlJoinType.CROSS_JOIN) {
            "No column equality conditions specified for join with type $joinType - this may " +
                "render invalid SQL!"
        }

        val andConditions = mutableListOf<SqlExpressionNode>()
        for (desc in columnEqualityDescriptions) {
            val leftColumn = SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = leftSourceAlias, columnName = desc.leftColumnAlias),
                shouldRenderTableAlias = true,
            )
            val rightColumn = SqlColumnReferenceExpression.create(
                colRef = SqlColumnReference(tableAlias = rightSourceAlias, columnName = desc.rightColumnAlias),
                shouldRenderTableAlias = true,
            )
            val equality = SqlComparisonExpression.create(
                leftExpr = leftColumn,
                comparison = SqlComparison.EQUALS,
                rightExpr = rightColumn,
            )
            if (desc.treatNullsAsEqual) {
                val nullComparison = SqlLogicalExpression.create(
                    operator = SqlLogicalOperator.AND,
                    args = listOf(SqlIsNullExpression.create(leftColumn), SqlIsNullExpression.create(rightColumn)),
                )
                andConditions += SqlLogicalExpression.create(
                    operator = SqlLogicalOperator.OR,
                    args = listOf(equality, nullComparison),
                )
            } else {
                andConditions += equality
            }
        }

        andConditions += additionalOnConditions

        val onCondition: SqlExpressionNode? = when (andConditions.size) {
            0 -> null
            1 -> andConditions[0]
            else -> SqlLogicalExpression.create(operator = SqlLogicalOperator.AND, args = andConditions)
        }

        return SqlJoinDescription(
            rightSource = rightSourceNode,
            rightSourceAlias = rightSourceAlias,
            onCondition = onCondition,
            joinType = joinType,
        )
    }

    /**
     * Convenience: equivalent to [makeColumnEqualitySqlJoinDescription] with no
     * [additionalOnConditions].
     */
    fun makeColumnEqualitySqlJoinDescription(
        rightSourceNode: SqlSelectStatementNode,
        leftSourceAlias: String,
        rightSourceAlias: String,
        columnEqualityDescriptions: List<ColumnEqualityDescription>,
        joinType: SqlJoinType,
    ): SqlJoinDescription = makeColumnEqualitySqlJoinDescription(
        rightSourceNode = rightSourceNode,
        leftSourceAlias = leftSourceAlias,
        rightSourceAlias = rightSourceAlias,
        columnEqualityDescriptions = columnEqualityDescriptions,
        joinType = joinType,
        additionalOnConditions = emptyList(),
    )

    /**
     * Make a join description to link two base-output `SqlDataSet`s by matching entities.
     *
     * In addition to the entity-equality condition this ensures datasets are joined on every
     * partition column and accounts for validity windows when those are defined on either side.
     *
     * Port of `make_base_output_join_description`.
     */
    fun makeBaseOutputJoinDescription(
        leftDataSet: AnnotatedSqlDataSet,
        rightDataSet: AnnotatedSqlDataSet,
        joinDescription: JoinDescription,
    ): SqlJoinDescription {
        val joinOnEntity = joinDescription.joinOnEntity

        val columnEqualityDescriptions = mutableListOf<ColumnEqualityDescription>()
        if (joinOnEntity != null) {
            val leftEntityCols = leftDataSet.dataSet.columnAssociationsForEntity(joinOnEntity)
                .map { it.columnName }
            val rightEntityCols = rightDataSet.dataSet.columnAssociationsForEntity(joinOnEntity)
                .map { it.columnName }
            check(leftEntityCols.size == rightEntityCols.size) {
                "Cannot construct join - the number of columns on the left ($leftEntityCols) side " +
                    "of the join does not match the right ($rightEntityCols)."
            }
            for (i in leftEntityCols.indices) {
                columnEqualityDescriptions += ColumnEqualityDescription(
                    leftColumnAlias = leftEntityCols[i],
                    rightColumnAlias = rightEntityCols[i],
                    treatNullsAsEqual = false,
                )
            }
        }

        // Partition dimension joins.
        for (dim in joinDescription.joinOnPartitionDimensions) {
            columnEqualityDescriptions += ColumnEqualityDescription(
                leftColumnAlias = leftDataSet.dataSet
                    .columnAssociationForDimension(dim.startNodeDimensionSpec).columnName,
                rightColumnAlias = rightDataSet.dataSet
                    .columnAssociationForDimension(dim.nodeToJoinDimensionSpec).columnName,
                treatNullsAsEqual = false,
            )
        }

        // Partition time-dimension joins.
        for (timeDim in joinDescription.joinOnPartitionTimeDimensions) {
            columnEqualityDescriptions += ColumnEqualityDescription(
                leftColumnAlias = leftDataSet.dataSet
                    .columnAssociationForTimeDimension(timeDim.startNodeTimeDimensionSpec).columnName,
                rightColumnAlias = rightDataSet.dataSet
                    .columnAssociationForTimeDimension(timeDim.nodeToJoinTimeDimensionSpec).columnName,
                treatNullsAsEqual = false,
            )
        }

        val validityConditions = makeValidityWindowOnConditions(leftDataSet, rightDataSet, joinDescription)

        return makeColumnEqualitySqlJoinDescription(
            rightSourceNode = rightDataSet.dataSet.checkedSqlSelectNode,
            leftSourceAlias = leftDataSet.alias,
            rightSourceAlias = rightDataSet.alias,
            columnEqualityDescriptions = columnEqualityDescriptions,
            joinType = joinDescription.joinType,
            additionalOnConditions = validityConditions,
        )
    }

    private fun makeValidityWindowOnConditions(
        leftDataSet: AnnotatedSqlDataSet,
        rightDataSet: AnnotatedSqlDataSet,
        joinDescription: JoinDescription,
    ): List<SqlExpressionNode> {
        val validityWindow = joinDescription.validityWindow ?: return emptyList()

        val leftMetricTimeInstances = leftDataSet.dataSet.metricTimeDimensionInstances
            .filter { !it.spec.hasCustomGrain }
            .sortedWith(
                compareBy({ it.spec.baseGranularitySortKey }, { it.spec.entityLinks.size }),
            )
        check(leftMetricTimeInstances.isNotEmpty()) {
            "Cannot process join to data set with alias ${rightDataSet.alias} because it has a " +
                "validity window set: $validityWindow, but source data set with alias " +
                "${leftDataSet.alias} does not have a metric time dimension we can use for the " +
                "window join!"
        }
        val leftTimeDimName = leftDataSet.dataSet
            .columnAssociationForTimeDimension(leftMetricTimeInstances[0].spec).columnName
        val startName = rightDataSet.dataSet
            .columnAssociationForTimeDimension(validityWindow.windowStartDimension).columnName
        val endName = rightDataSet.dataSet
            .columnAssociationForTimeDimension(validityWindow.windowEndDimension).columnName

        return listOf(
            makeTimeWindowJoinCondition(
                leftSourceAlias = leftDataSet.alias,
                leftSourceTimeDimensionName = leftTimeDimName,
                rightSourceAlias = rightDataSet.alias,
                windowStartDimensionName = startName,
                windowEndDimensionName = endName,
            ),
        )
    }

    private fun makeTimeWindowJoinCondition(
        leftSourceAlias: String,
        leftSourceTimeDimensionName: String,
        rightSourceAlias: String,
        windowStartDimensionName: String,
        windowEndDimensionName: String,
    ): SqlLogicalExpression {
        val leftTime = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = leftSourceAlias, columnName = leftSourceTimeDimensionName),
            shouldRenderTableAlias = true,
        )
        val windowStart = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = rightSourceAlias, columnName = windowStartDimensionName),
            shouldRenderTableAlias = true,
        )
        val windowEnd = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = rightSourceAlias, columnName = windowEndDimensionName),
            shouldRenderTableAlias = true,
        )
        val startCond = SqlComparisonExpression.create(
            leftExpr = leftTime,
            comparison = SqlComparison.GREATER_THAN_OR_EQUALS,
            rightExpr = windowStart,
        )
        val endByTime = SqlComparisonExpression.create(
            leftExpr = leftTime,
            comparison = SqlComparison.LESS_THAN,
            rightExpr = windowEnd,
        )
        val endIsNull = SqlIsNullExpression.create(windowEnd)
        val endCond = SqlLogicalExpression.create(
            operator = SqlLogicalOperator.OR,
            args = listOf(endByTime, endIsNull),
        )
        return SqlLogicalExpression.create(
            operator = SqlLogicalOperator.AND,
            args = listOf(startCond, endCond),
        )
    }

    /**
     * Build the join description for combining two output datasets.
     *
     * Port of `make_join_description_for_combining_datasets`. When [joinType] is FULL OUTER,
     * the conditions use `COALESCE(...)` of every previously seen table alias to ensure rows
     * with a NULL key on one side are unified properly. For non-FULL-OUTER cases the join
     * uses null-safe column-equality semantics.
     */
    fun makeJoinDescriptionForCombiningDatasets(
        fromDataSet: AnnotatedSqlDataSet,
        joinDataSet: AnnotatedSqlDataSet,
        joinType: SqlJoinType,
        columnNames: List<String>,
        tableAliasesForCoalesce: List<String>,
    ): SqlJoinDescription {
        if (joinType == SqlJoinType.FULL_OUTER) {
            check(columnNames.isNotEmpty()) {
                "Attempting to do a FULL OUTER JOIN to combine metrics, but no columns were " +
                    "provided for join keys!"
            }
            val equalityExprs = columnNames.map { colName ->
                makeEqualityExpressionForFullOuterJoin(
                    tableAliasesInCoalesce = tableAliasesForCoalesce,
                    rightTableAlias = joinDataSet.alias,
                    columnAlias = colName,
                )
            }
            val onCondition: SqlExpressionNode = if (equalityExprs.size > 1) {
                SqlLogicalExpression.create(operator = SqlLogicalOperator.AND, args = equalityExprs)
            } else {
                equalityExprs[0]
            }
            return SqlJoinDescription(
                rightSource = joinDataSet.dataSet.checkedSqlSelectNode,
                rightSourceAlias = joinDataSet.alias,
                onCondition = onCondition,
                joinType = joinType,
            )
        }
        val columnEqualityDescriptions = columnNames.map { name ->
            ColumnEqualityDescription(
                leftColumnAlias = name,
                rightColumnAlias = name,
                treatNullsAsEqual = true,
            )
        }
        return makeColumnEqualitySqlJoinDescription(
            rightSourceNode = joinDataSet.dataSet.checkedSqlSelectNode,
            leftSourceAlias = fromDataSet.alias,
            rightSourceAlias = joinDataSet.alias,
            columnEqualityDescriptions = columnEqualityDescriptions,
            joinType = joinType,
        )
    }

    private fun makeEqualityExpressionForFullOuterJoin(
        tableAliasesInCoalesce: List<String>,
        rightTableAlias: String,
        columnAlias: String,
    ): SqlExpressionNode = SqlComparisonExpression.create(
        leftExpr = SqlExpressionBuilder.makeCoalescedExpr(tableAliasesInCoalesce, columnAlias),
        comparison = SqlComparison.EQUALS,
        rightExpr = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = rightTableAlias, columnName = columnAlias),
            shouldRenderTableAlias = true,
        ),
    )

    /**
     * Build the join condition for a time-range window: `base.ds <= cmp.ds AND base.ds > cmp.ds - window`.
     *
     * Port of `_make_time_range_window_join_condition`.
     */
    private fun makeTimeRangeWindowJoinCondition(
        baseDataSet: AnnotatedSqlDataSet,
        timeComparisonDataset: AnnotatedSqlDataSet,
        window: TimeWindow?,
        grainToDate: TimeGranularity?,
    ): SqlLogicalExpression {
        if (window != null && grainToDate != null) {
            error("Exactly one of window / grainToDate may be set, but both were provided.")
        }
        val baseExpr = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(
                tableAlias = baseDataSet.alias,
                columnName = baseDataSet.checkedMetricTimeColumnName,
            ),
            shouldRenderTableAlias = true,
        )
        val cmpExpr = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(
                tableAlias = timeComparisonDataset.alias,
                columnName = timeComparisonDataset.checkedMetricTimeColumnName,
            ),
            shouldRenderTableAlias = true,
        )

        val endOfRange = SqlComparisonExpression.create(
            leftExpr = baseExpr,
            comparison = SqlComparison.LESS_THAN_OR_EQUALS,
            rightExpr = cmpExpr,
        )

        val comparisonExprs = mutableListOf<SqlExpressionNode>(endOfRange)
        if (window != null) {
            comparisonExprs += SqlComparisonExpression.create(
                leftExpr = baseExpr,
                comparison = SqlComparison.GREATER_THAN,
                rightExpr = SqlSubtractTimeIntervalExpression.create(
                    arg = cmpExpr,
                    count = window.count,
                    granularity = errorIfNotStandardGrain(
                        inputGranularity = window.granularity,
                        context = null,
                    ),
                ),
            )
        } else if (grainToDate != null) {
            comparisonExprs += SqlComparisonExpression.create(
                leftExpr = baseExpr,
                comparison = SqlComparison.GREATER_THAN_OR_EQUALS,
                rightExpr = SqlDateTruncExpression.create(timeGranularity = grainToDate, arg = cmpExpr),
            )
        }

        return SqlLogicalExpression.create(operator = SqlLogicalOperator.AND, args = comparisonExprs)
    }

    /**
     * Make a join description for base-to-conversion event joins (conversion metrics).
     *
     * Port of `make_join_conversion_join_description`.
     */
    fun makeJoinConversionJoinDescription(
        node: JoinConversionEventsNode,
        baseDataSet: AnnotatedSqlDataSet,
        conversionDataSet: AnnotatedSqlDataSet,
        columnEqualityDescriptions: List<ColumnEqualityDescription>,
    ): SqlJoinDescription {
        val windowCondition = makeTimeRangeWindowJoinCondition(
            baseDataSet = baseDataSet,
            timeComparisonDataset = conversionDataSet,
            window = node.window,
            grainToDate = null,
        )
        return makeColumnEqualitySqlJoinDescription(
            rightSourceNode = conversionDataSet.dataSet.checkedSqlSelectNode,
            leftSourceAlias = baseDataSet.alias,
            rightSourceAlias = conversionDataSet.alias,
            columnEqualityDescriptions = columnEqualityDescriptions,
            joinType = SqlJoinType.INNER,
            additionalOnConditions = listOf(windowCondition),
        )
    }

    /**
     * Make a join description to connect a cumulative-metric input to a time-spine dataset.
     *
     * Cumulative metrics must be joined against a time spine in a backward-looking fashion,
     * with a range determined by [node.window][JoinOverTimeRangeNode.window] (delta against
     * metric_time) and optional cumulative grain.
     *
     * Port of `make_cumulative_metric_time_range_join_description`.
     */
    fun makeCumulativeMetricTimeRangeJoinDescription(
        node: JoinOverTimeRangeNode,
        metricDataSet: AnnotatedSqlDataSet,
        timeSpineDataSet: AnnotatedSqlDataSet,
    ): SqlJoinDescription {
        val cumulativeCondition = makeTimeRangeWindowJoinCondition(
            baseDataSet = metricDataSet,
            timeComparisonDataset = timeSpineDataSet,
            window = node.window,
            grainToDate = node.grainToDate,
        )
        return SqlJoinDescription(
            rightSource = metricDataSet.dataSet.checkedSqlSelectNode,
            rightSourceAlias = metricDataSet.alias,
            onCondition = cumulativeCondition,
            joinType = SqlJoinType.INNER,
        )
    }

    /**
     * Build the join expression connecting a metric to a time-spine dataset.
     *
     * Port of `make_join_to_time_spine_join_description`.
     */
    fun makeJoinToTimeSpineJoinDescription(
        node: JoinToTimeSpineNode,
        timeSpineAlias: String,
        timeSpineColumnName: String,
        parentColumnName: String,
        parentSqlSelectNode: SqlSelectStatementNode,
        parentAlias: String,
    ): SqlJoinDescription {
        var leftExpr: SqlExpressionNode = SqlColumnReferenceExpression.create(
            colRef = SqlColumnReference(tableAlias = timeSpineAlias, columnName = timeSpineColumnName),
            shouldRenderTableAlias = true,
        )
        val standardOffsetWindow = node.standardOffsetWindow
        val offsetToGrain = node.offsetToGrain
        if (standardOffsetWindow != null) {
            leftExpr = SqlSubtractTimeIntervalExpression.create(
                arg = leftExpr,
                count = standardOffsetWindow.count,
                granularity = errorIfNotStandardGrain(
                    inputGranularity = standardOffsetWindow.granularity,
                    context = null,
                ),
            )
        } else if (offsetToGrain != null) {
            leftExpr = SqlDateTruncExpression.create(timeGranularity = offsetToGrain, arg = leftExpr)
        }

        return SqlJoinDescription(
            rightSource = parentSqlSelectNode,
            rightSourceAlias = parentAlias,
            onCondition = SqlComparisonExpression.create(
                leftExpr = leftExpr,
                comparison = SqlComparison.EQUALS,
                rightExpr = SqlColumnReferenceExpression.create(
                    colRef = SqlColumnReference(tableAlias = parentAlias, columnName = parentColumnName),
                    shouldRenderTableAlias = true,
                ),
            ),
            joinType = node.joinType,
        )
    }
}
