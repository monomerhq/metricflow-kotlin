package cc.monomer.metricflow.domain.query.resolution

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.lookup.SemanticModelDerivation
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.GroupByItemResolutionDag
import cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec

/**
 * Pairs a resolver input with the issues that arose while resolving it.
 *
 * Port of `metricflow_semantics.query.query_resolution.InputToIssueSetMappingItem`.
 */
data class InputToIssueSetMappingItem(
    val resolverInput: MetricFlowQueryResolverInput,
    val issueSet: MetricFlowQueryResolutionIssueSet,
)

/**
 * Collected mapping from inputs to issue sets, with merge / fold semantics.
 *
 * Port of `metricflow_semantics.query.query_resolution.InputToIssueSetMapping`.
 */
data class InputToIssueSetMapping(
    val items: List<InputToIssueSetMappingItem>,
) : Mergeable<InputToIssueSetMapping> {

    /** True iff any contained issue-set has issues. */
    val hasIssues: Boolean get() = items.any { it.issueSet.hasIssues }

    /** Concat every contained issue set into one. */
    val mergedIssueSet: MetricFlowQueryResolutionIssueSet
        get() = Mergeable.mergeIterable(
            items = items.map { it.issueSet },
            empty = MetricFlowQueryResolutionIssueSet.EMPTY,
        )

    /** Number of mapping entries. */
    val size: Int get() = items.size

    override fun merge(other: InputToIssueSetMapping): InputToIssueSetMapping =
        InputToIssueSetMapping(items = items + other.items)

    companion object {
        /** Empty mapping. */
        val EMPTY: InputToIssueSetMapping = InputToIssueSetMapping(emptyList())

        /** Build a singleton from one item. */
        fun fromOneItem(
            resolverInput: MetricFlowQueryResolverInput,
            issueSet: MetricFlowQueryResolutionIssueSet,
        ): InputToIssueSetMapping = InputToIssueSetMapping(
            items = listOf(InputToIssueSetMappingItem(resolverInput, issueSet)),
        )
    }
}

/**
 * Final output of [cc.monomer.metricflow.domain.query.MetricFlowQueryResolver].
 *
 * Port of `metricflow_semantics.query.query_resolution.MetricFlowQueryResolution`.
 *
 * - [querySpec] is `null` if resolution had errors.
 * - [resolutionDag] is `null` if construction failed at the DAG stage.
 * - [filterSpecLookup] aggregates per-filter lookup keys + resolutions.
 * - [inputToIssueSet] groups issues per input for error reporting.
 * - [queriedSemanticModels] tracks the manifest models the query touches —
 *   doubles as the [SemanticModelDerivation] implementation.
 */
data class MetricFlowQueryResolution(
    val querySpec: MetricFlowQuerySpec?,
    val resolutionDag: GroupByItemResolutionDag?,
    val filterSpecLookup: FilterSpecResolutionLookUp,
    val inputToIssueSet: InputToIssueSetMapping,
    val queriedSemanticModels: List<SemanticModelReference>,
) : SemanticModelDerivation {

    override val derivedFromSemanticModels: List<SemanticModelReference>
        get() = queriedSemanticModels

    /** Returns [querySpec] if resolution succeeded; throws otherwise. */
    val checkedQuerySpec: MetricFlowQuerySpec
        get() {
            check(!inputToIssueSet.hasIssues) {
                "Can't get the query spec because errors were present in the resolution: $inputToIssueSet"
            }
            return checkNotNull(querySpec) { "If there were no errors, querySpec should have been populated." }
        }

    /** True iff resolution surfaced any issues (input issues or filter-spec errors). */
    val hasErrors: Boolean
        get() = inputToIssueSet.hasIssues || filterSpecLookup.hasErrors
}
