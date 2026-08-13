package cc.monomer.metricflow.domain.query.issue

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.query.group_by.PathPrefixable
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput

/**
 * Severity of a [MetricFlowQueryResolutionIssue].
 *
 * Port of `metricflow_semantics.query.issues.issues_base.MetricFlowQueryIssueType`.
 *
 * Today only `ERROR` is defined — Python notes that warnings are planned.
 */
enum class MetricFlowQueryIssueType {
    ERROR,
}

/**
 * Base class for every issue raised during query resolution.
 *
 * Port of `metricflow_semantics.query.issues.issues_base.MetricFlowQueryResolutionIssue`.
 *
 * Each concrete subclass carries:
 *
 * - the [issueType] (`ERROR`),
 * - the chain of [parentIssues] (hierarchical issues, common for derived
 *   metric resolution failures),
 * - the resolution-DAG [queryResolutionPath] where the issue surfaced,
 * - a [uiDescription] used to produce the final error message.
 *
 * Subclasses must implement [withPathPrefix] to re-anchor themselves when a
 * parent resolution path is prepended.
 */
abstract class MetricFlowQueryResolutionIssue : PathPrefixable<MetricFlowQueryResolutionIssue> {
    abstract val issueType: MetricFlowQueryIssueType
    abstract val parentIssues: List<MetricFlowQueryResolutionIssue>
    abstract val queryResolutionPath: MetricFlowQueryResolutionPath

    /** UI string built from the associated input. */
    abstract fun uiDescription(associatedInput: MetricFlowQueryResolverInput): String

    abstract override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): MetricFlowQueryResolutionIssue
}

/**
 * A bag of [MetricFlowQueryResolutionIssue]s, mergeable like Python's
 * `MetricFlowQueryResolutionIssueSet`.
 *
 * Port of `metricflow_semantics.query.issues.issues_base.MetricFlowQueryResolutionIssueSet`.
 *
 * Implementations of the resolver merge these sets along the DAG. The
 * [hasErrors] / [errors] accessors mirror the Python predicates exactly.
 */
data class MetricFlowQueryResolutionIssueSet(
    val issues: List<MetricFlowQueryResolutionIssue>,
) : Mergeable<MetricFlowQueryResolutionIssueSet>,
    PathPrefixable<MetricFlowQueryResolutionIssueSet> {

    /** Subset of [issues] with `ERROR` severity. */
    val errors: List<MetricFlowQueryResolutionIssue>
        get() = issues.filter { it.issueType == MetricFlowQueryIssueType.ERROR }

    /** True iff [errors] is non-empty. */
    val hasErrors: Boolean get() = errors.isNotEmpty()

    /** True iff any issues are present. */
    val hasIssues: Boolean get() = issues.isNotEmpty()

    /** Number of issues in the set. */
    val size: Int get() = issues.size

    override fun merge(other: MetricFlowQueryResolutionIssueSet): MetricFlowQueryResolutionIssueSet =
        MetricFlowQueryResolutionIssueSet(issues = issues + other.issues)

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): MetricFlowQueryResolutionIssueSet =
        MetricFlowQueryResolutionIssueSet(issues = issues.map { it.withPathPrefix(pathPrefix) })

    /** Return a new set with [issue] appended. */
    fun addIssue(issue: MetricFlowQueryResolutionIssue): MetricFlowQueryResolutionIssueSet =
        MetricFlowQueryResolutionIssueSet(issues = issues + issue)

    companion object {
        /** Empty set used as the merge identity. */
        val EMPTY: MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet(emptyList())

        /** Build a singleton set from one issue. */
        fun fromIssue(issue: MetricFlowQueryResolutionIssue): MetricFlowQueryResolutionIssueSet =
            MetricFlowQueryResolutionIssueSet(issues = listOf(issue))

        /** Build a set from a list of issues. */
        fun fromIssues(issues: List<MetricFlowQueryResolutionIssue>): MetricFlowQueryResolutionIssueSet =
            MetricFlowQueryResolutionIssueSet(issues = issues)
    }
}
