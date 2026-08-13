package cc.monomer.metricflow.domain.query.resolution

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.parameterset.DimensionCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.EntityCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.MetricCallParameterSet
import cc.monomer.metricflow.domain.manifest.model.parameterset.TimeDimensionCallParameterSet
import cc.monomer.metricflow.domain.query.group_by.PathPrefixable
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.FilterSpecResolutionLookupPlaceholder
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern

/**
 * One of the four Jinja-style call-parameter variants used inside a
 * where-filter template.
 *
 * Mirrors Python's `CallParameterSet = Union[
 *   DimensionCallParameterSet, TimeDimensionCallParameterSet,
 *   EntityCallParameterSet, MetricCallParameterSet,
 * ]` (in
 * `metricflow_semantics.query.group_by_item.filter_spec_resolution.filter_spec_lookup`).
 *
 * Kotlin promotes the union to a sealed family so `when` over the kinds is
 * compile-time exhaustive.
 */
sealed interface CallParameterSet {
    @JvmInline value class Dimension(val value: DimensionCallParameterSet) : CallParameterSet
    @JvmInline value class TimeDimension(val value: TimeDimensionCallParameterSet) : CallParameterSet
    @JvmInline value class Entity(val value: EntityCallParameterSet) : CallParameterSet
    @JvmInline value class Metric(val value: MetricCallParameterSet) : CallParameterSet
}

/**
 * Key combining a where-filter location and a call-parameter set.
 *
 * Port of `ResolvedSpecLookUpKey`.
 */
data class ResolvedSpecLookUpKey(
    val filterLocation: WhereFilterLocation,
    val callParameterSet: CallParameterSet,
)

/**
 * Pattern association recorded inside [FilterSpecResolution] for one
 * group-by-item in a where filter.
 *
 * Port of `PatternAssociationForWhereFilterGroupByItem`.
 */
data class PatternAssociationForWhereFilterGroupByItem(
    val callParameterSet: CallParameterSet,
    val objectBuilderStr: String,
    val specPattern: SpecPattern,
)

/**
 * Where-filter intersection that couldn't be parsed (e.g. Jinja error).
 *
 * Port of `NonParsableFilterResolution`.
 */
data class NonParsableFilterResolution(
    val filterLocationPath: MetricFlowQueryResolutionPath,
    val whereFilterIntersection: WhereFilterIntersection,
    val issueSet: MetricFlowQueryResolutionIssueSet,
) : PathPrefixable<NonParsableFilterResolution> {

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): NonParsableFilterResolution =
        NonParsableFilterResolution(
            filterLocationPath = filterLocationPath.withPathPrefix(pathPrefix),
            whereFilterIntersection = whereFilterIntersection,
            issueSet = issueSet.withPathPrefix(pathPrefix),
        )
}

/**
 * Per-key resolution record: pattern + resolved spec set + issues.
 *
 * Port of `FilterSpecResolution`.
 *
 * Python enforces "at most one resolved spec" via `__post_init__`; the
 * Kotlin port reproduces the check in `init`.
 */
data class FilterSpecResolution(
    val lookupKey: ResolvedSpecLookUpKey,
    val whereFilterIntersection: WhereFilterIntersection,
    val resolvedGroupByItemSet: GroupByItemSet,
    val specPattern: SpecPattern,
    val issueSet: MetricFlowQueryResolutionIssueSet,
    /** Filter resolution path — used only for error messages. */
    val filterLocationPath: MetricFlowQueryResolutionPath,
    val objectBuilderStr: String,
) {
    init {
        val n = resolvedGroupByItemSet.specs.size
        check(n <= 1) {
            "Found $n specs in $resolvedGroupByItemSet, but a valid FilterSpecResolution should " +
                "contain either 0 or 1 resolved specs."
        }
    }

    /** The resolved spec if present, else `null`. */
    val resolvedSpec: AnnotatedSpec?
        get() = resolvedGroupByItemSet.annotatedSpecs.singleOrNull()
}

/**
 * Lookup from `(filter_location, call_parameter_set)` pairs to resolved
 * specs.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.filter_spec_resolution.filter_spec_lookup.FilterSpecResolutionLookUp`.
 *
 * Used by `:domain:dataflow` (W9) to determine which specs each filter in
 * the query references. Implements [FilterSpecResolutionLookupPlaceholder]
 * so it slots into [MetricFlowQuerySpec.filterSpecResolutionLookup] without
 * forcing the spec layer to know the concrete type.
 */
data class FilterSpecResolutionLookUp(
    val specResolutions: List<FilterSpecResolution>,
    val nonParsableResolutions: List<NonParsableFilterResolution>,
) : FilterSpecResolutionLookupPlaceholder,
    Mergeable<FilterSpecResolutionLookUp> {

    /** True iff any resolution has an error-level issue. */
    val hasErrors: Boolean
        get() = nonParsableResolutions.any { it.issueSet.hasErrors } ||
            specResolutions.any { it.issueSet.hasErrors }

    /** True iff any resolution has an issue (error or warning). */
    val hasIssues: Boolean
        get() = nonParsableResolutions.any { it.issueSet.hasIssues } ||
            specResolutions.any { it.issueSet.hasIssues }

    /** All resolutions matching [key]. Empty if none recorded. */
    fun getSpecResolutions(key: ResolvedSpecLookUpKey): List<FilterSpecResolution> =
        specResolutions.filter { it.lookupKey == key }

    /** True iff at least one resolution is recorded for [key]. */
    fun specResolutionExists(key: ResolvedSpecLookUpKey): Boolean = getSpecResolutions(key).isNotEmpty()

    /**
     * Return the resolved spec for [key], or throw a [RuntimeException] if
     * the key has no recorded resolution / no resolved spec on the
     * resolution.
     *
     * Mirrors Python's `checked_resolved_spec`.
     */
    fun checkedResolvedSpec(key: ResolvedSpecLookUpKey): AnnotatedSpec {
        val resolutions = getSpecResolutions(key)
        if (resolutions.isEmpty()) {
            throw RuntimeException(
                "Unable to find a resolved spec for key=$key. All resolutions: $specResolutions",
            )
        }
        val resolution = resolutions.first()
        return resolution.resolvedSpec
            ?: throw RuntimeException("Expected a resolution with a resolved spec, but got: $resolution.")
    }

    override fun merge(other: FilterSpecResolutionLookUp): FilterSpecResolutionLookUp = FilterSpecResolutionLookUp(
        specResolutions = specResolutions + other.specResolutions,
        nonParsableResolutions = nonParsableResolutions + other.nonParsableResolutions,
    )

    companion object {
        /** The empty lookup. */
        val EMPTY: FilterSpecResolutionLookUp = FilterSpecResolutionLookUp(
            specResolutions = emptyList(),
            nonParsableResolutions = emptyList(),
        )
    }
}
