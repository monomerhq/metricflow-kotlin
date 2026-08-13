package cc.monomer.metricflow.domain.query.filter

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilter
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.query.resolution.FilterSpecResolutionLookUp
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocation
import cc.monomer.metricflow.domain.semantic_graph.RenderedSpecTracker
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Render where-filter Jinja templates into [WhereFilterSpec] records.
 *
 * Port of `metricflow_semantics.specs.where_filter.where_filter_spec_factory.WhereFilterSpecFactory`.
 *
 * The factory:
 *
 * 1. Wraps the supplied [FilterSpecResolutionLookUp] + manifest custom
 *    granularity names + column-association resolver in a per-filter
 *    [RenderedSpecTracker].
 * 2. Renders each [WhereFilter] via a Jinja sandbox that exposes
 *    `Dimension(...)`, `TimeDimension(...)`, `Entity(...)`, and
 *    `Metric(...)` builders. Each builder turns the call into a
 *    [WhereFilterSpec]-suitable string and registers the matching
 *    [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec]
 *    with the tracker.
 * 3. Packages the rendered SQL + the tracker's collected annotated specs
 *    into a [WhereFilterSpec].
 *
 * **Partial port — Jinja sandbox deferred.** The Kotlin port shares the
 * fate of [cc.monomer.metricflow.domain.query.naming.ObjectBuilderNamingScheme]:
 * the Jinja sandbox is the entry point and has not yet been ported.
 * Until that lands, this factory accepts and returns empty filter
 * intersections, and throws on non-empty input — keeping the call-site
 * contract intact for the dataflow planner once both halves arrive.
 *
 * Once the Jinja parser ports, the body of [createFromWhereFilter] /
 * [createFromWhereFilterIntersection] should:
 *
 * - Build four sub-factories (`WhereFilterDimensionFactory`,
 *   `WhereFilterEntityFactory`, `WhereFilterMetricFactory`,
 *   `WhereFilterTimeDimensionFactory`).
 * - Wire those into a Jinja sandbox keyed on the four builder names.
 * - Render and pack as above.
 */
class WhereFilterSpecFactory(
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val specResolutionLookup: FilterSpecResolutionLookUp,
    private val customGrainNames: List<String>,
) {

    /** Build a single spec for one filter. */
    fun createFromWhereFilter(
        filterLocation: WhereFilterLocation,
        whereFilter: WhereFilter,
    ): WhereFilterSpec = createFromWhereFilterIntersection(
        filterLocation = filterLocation,
        filterIntersection = WhereFilterIntersection(whereFilters = listOf(whereFilter)),
    ).single()

    /** Build specs for every filter in the intersection. */
    fun createFromWhereFilterIntersection(
        filterLocation: WhereFilterLocation,
        filterIntersection: WhereFilterIntersection?,
    ): List<WhereFilterSpec> {
        if (filterIntersection == null || filterIntersection.whereFilters.isEmpty()) return emptyList()

        // Suppress unused warnings on resolution-lookup + tracker plumbing — these are reserved
        // for the full Jinja sandbox port (see KDoc). The minimal renderer below uses just the
        // column association resolver and the manifest's custom grain names.
        @Suppress("UNUSED_VARIABLE")
        val tracker = RenderedSpecTracker()
        @Suppress("UNUSED_VARIABLE")
        val lookup = specResolutionLookup
        @Suppress("UNUSED_VARIABLE")
        val location = filterLocation

        val renderer = WhereFilterTemplateRenderer(
            columnAssociationResolver = columnAssociationResolver,
            customGrainNames = customGrainNames.toSet(),
        )
        return filterIntersection.whereFilters.map { whereFilter ->
            val rendered = renderer.render(whereFilter.whereSqlTemplate)
            WhereFilterSpec.fromLinkableSpecs(
                whereSql = rendered.whereSql,
                bindParameters = SqlBindParameterSet(),
                linkableSpecs = rendered.usedSpecs,
            )
        }
    }
}
