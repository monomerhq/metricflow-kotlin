package cc.monomer.metricflow.domain.query.issue.parsing

import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryIssueType
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssue

/**
 * Helper to re-anchor [parentIssues] when applying a path prefix to a
 * concrete subclass. Used by every concrete issue's [withPathPrefix].
 */
internal fun List<MetricFlowQueryResolutionIssue>.withPathPrefix(
    pathPrefix: MetricFlowQueryResolutionPath,
): List<MetricFlowQueryResolutionIssue> = map { it.withPathPrefix(pathPrefix) }

/**
 * The user-supplied input string does not match any of the known naming
 * schemes (`listing__country`, `Dimension(...)`, etc.).
 *
 * Port of `StringInputParsingIssue`.
 */
data class StringInputParsingIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val inputStr: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "The input '$inputStr' does not match any of the known formats."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): StringInputParsingIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(inputStr: String): StringInputParsingIssue = StringInputParsingIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = MetricFlowQueryResolutionPath.EMPTY,
            inputStr = inputStr,
        )
    }
}

/** The metric name doesn't match any known metric. Port of `InvalidMetricIssue`. */
data class InvalidMetricIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val metricSuggestions: List<String>,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String = buildString {
        append("The given input does not exactly match any known metrics.\n\n")
        append("Suggestions:\n")
        append("  ").append(metricSuggestions)
    }

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidMetricIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            metricSuggestions: List<String>,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidMetricIssue = InvalidMetricIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            metricSuggestions = metricSuggestions,
        )
    }
}

/** Two query inputs would produce the same output column name. Port of `DuplicateOutputColumnIssue`. */
data class DuplicateOutputColumnIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val duplicateColumnNames: List<String>,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Query contains duplicate output column names: $duplicateColumnNames"

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): DuplicateOutputColumnIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            duplicateColumnNames: List<String>,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): DuplicateOutputColumnIssue = DuplicateOutputColumnIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            duplicateColumnNames = duplicateColumnNames,
        )
    }
}

/** The same metric name was specified twice. Port of `DuplicateMetricIssue`. */
data class DuplicateMetricIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val duplicateMetricNames: List<String>,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Query contains duplicate metrics: $duplicateMetricNames"

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): DuplicateMetricIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            duplicateMetricNames: List<String>,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): DuplicateMetricIssue = DuplicateMetricIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            duplicateMetricNames = duplicateMetricNames,
        )
    }
}

/** Cumulative metric requires `metric_time`. Port of `CumulativeMetricRequiresMetricTimeIssue`. */
data class CumulativeMetricRequiresMetricTimeIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val metricName: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Query for cumulative metric '$metricName' must include a metric_time group-by item."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): CumulativeMetricRequiresMetricTimeIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            metricName: String,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): CumulativeMetricRequiresMetricTimeIssue = CumulativeMetricRequiresMetricTimeIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            metricName = metricName,
        )
    }
}

/** Offset metric requires `metric_time`. Port of `OffsetMetricRequiresMetricTimeIssue`. */
data class OffsetMetricRequiresMetricTimeIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val metricName: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Query for metric '$metricName' uses an input with a time offset; queries must include metric_time."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): OffsetMetricRequiresMetricTimeIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            metricName: String,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): OffsetMetricRequiresMetricTimeIssue = OffsetMetricRequiresMetricTimeIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            metricName = metricName,
        )
    }
}

/** Query must specify at least one metric or group-by item. Port of `NoMetricOrGroupByIssue`. */
data class NoMetricOrGroupByIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Query must contain at least one metric or group-by item."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NoMetricOrGroupByIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): NoMetricOrGroupByIssue = NoMetricOrGroupByIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
        )
    }
}

/** Invalid limit value. Port of `InvalidLimitIssue`. */
data class InvalidLimitIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val limit: Int,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "The limit value $limit is invalid — it must be a positive integer."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidLimitIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            limit: Int,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidLimitIssue = InvalidLimitIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            limit = limit,
        )
    }
}

/** `min_max_only` was requested for a query with metrics. Port of `InvalidMinMaxOnlyIssue`. */
data class InvalidMinMaxOnlyIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "min_max_only cannot be combined with metrics or with multiple group-by items."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidMinMaxOnlyIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidMinMaxOnlyIssue = InvalidMinMaxOnlyIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
        )
    }
}

/** `apply_group_by` set incompatibly. Port of `InvalidApplyGroupByIssue`. */
data class InvalidApplyGroupByIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "apply_group_by can only be False when the query has no metrics."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidApplyGroupByIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidApplyGroupByIssue = InvalidApplyGroupByIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
        )
    }
}

/** Could not match an order-by entry to a metric or group-by item. Port of `InvalidOrderByItemIssue`. */
data class InvalidOrderByItemIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val orderByItem: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "The order-by item '$orderByItem' does not match any of the selected metrics or group-by items."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidOrderByItemIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            orderByItem: String,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidOrderByItemIssue = InvalidOrderByItemIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            orderByItem = orderByItem,
        )
    }
}
