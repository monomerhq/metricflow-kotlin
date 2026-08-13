package cc.monomer.metricflow.domain.spec.where

import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * Read-only abstraction over the "group-by-item set" referenced by a
 * [WhereFilterSpec].
 *
 * Port of the *interface* surface of
 * `metricflow_semantics.semantic_graph.attribute_resolution.group_by_item_set.GroupByItemSet`
 * as consumed by [WhereFilterSpec].
 *
 * The concrete implementation (`GroupByItemSet`) lives in `:domain:semantic-graph`
 * because it depends on `AnnotatedSpec` and the trie machinery. Exposing
 * just this view here lets [WhereFilterSpec] carry the W7c element-set
 * without inverting the module dependency direction (spec → semantic-graph).
 *
 * Callers that need richer access (annotated specs, derived-from semantic
 * models) must downcast to the concrete `GroupByItemSet`. The two API
 * surfaces commonly used by `:domain:spec` and `:domain:query` consumers
 * — [specs] and [isEmpty] — are exposed directly.
 */
interface LinkableSpecGroup {
    /** The contained linkable specs in insertion order. */
    val specs: List<LinkableInstanceSpec>

    /** True iff this group references no specs. */
    val isEmpty: Boolean
        get() = specs.isEmpty()

    companion object {
        /**
         * An empty [LinkableSpecGroup] used as the default value when a
         * [WhereFilterSpec] is constructed without an element-set wiring.
         */
        val EMPTY: LinkableSpecGroup = object : LinkableSpecGroup {
            override val specs: List<LinkableInstanceSpec> = emptyList()
        }
    }
}

/**
 * A WHERE filter, with the where-SQL template already rendered and the used
 * group-by-items extracted.
 *
 * Port of `metricflow_semantics.specs.where_filter.where_filter_spec.WhereFilterSpec`.
 *
 * Example: `WhereFilter("{{ Dimension('listing__country') }} == 'US'")` becomes
 * ```
 * WhereFilterSpec(
 *     whereSql = "listing__country == 'US'",
 *     bindParameters = SqlBindParameterSet.EMPTY,
 *     elementSet = GroupByItemSet(annotatedSpecs = [...]),
 * )
 * ```
 *
 * The Python class carries a `GroupByItemSet element_set` field as its
 * source of truth and exposes `linkable_specs` as a `cached_property`
 * derived from it. The Kotlin port follows the same model: [elementSet]
 * is the primary field and [linkableSpecs] is a thin projection.
 *
 * Because the concrete `GroupByItemSet` type lives in
 * `:domain:semantic-graph` (and depends on `:domain:spec`, so a back-edge
 * is impossible), the field is typed as the [LinkableSpecGroup] interface
 * declared in this module. Callers in `:domain:query` and below construct
 * `WhereFilterSpec` with a real `GroupByItemSet`; W7b's own internal
 * consumers continue to construct with `LinkableSpecGroup.EMPTY` (the
 * default propagated by the [withoutLinkableSpecs] convenience).
 */
data class WhereFilterSpec(
    val whereSql: String,
    val bindParameters: SqlBindParameterSet,
    val elementSet: LinkableSpecGroup,
) {
    /** Projection of [elementSet] to bare linkable specs (matches Python's `linkable_specs`). */
    val linkableSpecs: List<LinkableInstanceSpec>
        get() = elementSet.specs

    companion object {
        /**
         * Convenience constructor matching the W7b call sites that built a
         * [WhereFilterSpec] from a bare list of linkable specs. Used by
         * existing tests and by the W4-era spec consumers that don't yet
         * carry annotation metadata.
         */
        fun fromLinkableSpecs(
            whereSql: String,
            bindParameters: SqlBindParameterSet,
            linkableSpecs: List<LinkableInstanceSpec>,
        ): WhereFilterSpec = WhereFilterSpec(
            whereSql = whereSql,
            bindParameters = bindParameters,
            elementSet = object : LinkableSpecGroup {
                override val specs: List<LinkableInstanceSpec> = linkableSpecs
            },
        )
    }
}
