package cc.monomer.metricflow.domain.query.issue.filter_spec_resolver

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilter
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryIssueType
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssue
import cc.monomer.metricflow.domain.query.issue.parsing.withPathPrefix

/**
 * A where-filter could not be parsed (Jinja / call-parameter-set error).
 *
 * Port of `metricflow_semantics.query.issues.filter_spec_resolver.invalid_where.WhereFilterParsingIssue`.
 */
data class WhereFilterParsingIssue(
    override val issueType: MetricFlowQueryIssueType,
    override val parentIssues: List<MetricFlowQueryResolutionIssue>,
    override val queryResolutionPath: MetricFlowQueryResolutionPath,
    val whereFilter: WhereFilter,
    val parseException: Throwable,
) : MetricFlowQueryResolutionIssue() {

    override fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String = buildString {
        append("Error parsing where filter:\n\n")
        append("  ").append(whereFilter.whereSqlTemplate).append("\n\n")
        append("Got exception:\n\n")
        append("  ").append(parseException.toString())
    }

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): WhereFilterParsingIssue = copy(
        parentIssues = parentIssues.withPathPrefix(pathPrefix),
        queryResolutionPath = queryResolutionPath.withPathPrefix(pathPrefix),
    )

    companion object {
        fun fromParameters(
            whereFilter: WhereFilter,
            parseException: Throwable,
            queryResolutionPath: MetricFlowQueryResolutionPath,
        ): WhereFilterParsingIssue = WhereFilterParsingIssue(
            issueType = MetricFlowQueryIssueType.ERROR,
            parentIssues = emptyList(),
            queryResolutionPath = queryResolutionPath,
            whereFilter = whereFilter,
            parseException = parseException,
        )
    }
}
