package cc.monomer.metricflow.domain.query.issue.group_by_item_resolver

import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryIssueType
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssue
import cc.monomer.metricflow.domain.query.issue.parsing.withPathPrefix

/**
 * Group-by-item resolver issues.
 *
 * Port of the concrete subclasses under
 * `metricflow_semantics.query.issues.group_by_item_resolver`.
 */

/**
 * The pushed-down candidates produce more than one match for a group-by item.
 * Port of `AmbiguousGroupByItemIssue`.
 */
data class AmbiguousGroupByItemIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val matchingSpecs: List<LinkableInstanceSpec>,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "The group-by item is ambiguous — it matches more than one spec: $matchingSpecs."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): AmbiguousGroupByItemIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            matchingSpecs: List<LinkableInstanceSpec>,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): AmbiguousGroupByItemIssue = AmbiguousGroupByItemIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            matchingSpecs = matchingSpecs,
        )
    }
}

/** Use of `date_part` is invalid in the current location. Port of `InvalidUseOfDatePartIssue`. */
data class InvalidUseOfDatePartIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val datePartName: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "The use of date_part='$datePartName' is invalid in this position."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): InvalidUseOfDatePartIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            datePartName: String,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): InvalidUseOfDatePartIssue = InvalidUseOfDatePartIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            datePartName = datePartName,
        )
    }
}

/** Multiple distinct join paths produce different candidates. Port of `MultipleJoinPathsIssue`. */
data class MultipleJoinPathsIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val matchingSpecs: List<LinkableInstanceSpec>,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "More than one join path exists for the requested group-by item: $matchingSpecs."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): MultipleJoinPathsIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            matchingSpecs: List<LinkableInstanceSpec>,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): MultipleJoinPathsIssue = MultipleJoinPathsIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            matchingSpecs = matchingSpecs,
        )
    }
}

/** No metric inputs share the requested group-by items. Port of `NoCommonItemsInParents`. */
data class NoCommonItemsInParentsIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "No common group-by items are available across the parents in the resolution DAG."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NoCommonItemsInParentsIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
            parentIssues: List<MetricFlowQueryResolutionIssue>,
        ): NoCommonItemsInParentsIssue = NoCommonItemsInParentsIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = parentIssues,
            queryResolutionPath = queryResolutionPath,
        )
    }
}

/** No matching items for a metric-less query. Port of `NoMatchingItemsForNoMetricsQuery`. */
data class NoMatchingItemsForNoMetricsQueryIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "No matching group-by items are available for a query with no metrics."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NoMatchingItemsForNoMetricsQueryIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): NoMatchingItemsForNoMetricsQueryIssue = NoMatchingItemsForNoMetricsQueryIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
        )
    }
}

/** No matching items for a specific simple-metric source node. Port of `NoMatchingItemsForSimpleMetric`. */
data class NoMatchingItemsForSimpleMetricIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val metricName: String,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "Simple metric '$metricName' has no matching group-by items for this query."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NoMatchingItemsForSimpleMetricIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            metricName: String,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): NoMatchingItemsForSimpleMetricIssue = NoMatchingItemsForSimpleMetricIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            metricName = metricName,
        )
    }
}

/** No parent candidates were produced (e.g. for a derived metric). Port of `NoParentCandidates`. */
data class NoParentCandidatesIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String =
        "No parent candidates remain after intersection — a parent metric resolution had errors."

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NoParentCandidatesIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            queryResolutionPath: MetricFlowQueryResolutionPath,
            parentIssues: List<MetricFlowQueryResolutionIssue>,
        ): NoParentCandidatesIssue = NoParentCandidatesIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = parentIssues,
            queryResolutionPath = queryResolutionPath,
        )
    }
}
