package cc.monomer.metricflow.domain.query.parameter

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.query.input.ResolverInputForGroupByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForMetric
import cc.monomer.metricflow.domain.query.input.ResolverInputForOrderByItem

/**
 * Query-parameter port interfaces.
 *
 * Port of `metricflow_semantics.protocols.query_parameter.*`.
 *
 * These mirror Python's `@runtime_checkable` Protocols — Kotlin uses
 * plain interfaces with the same method signatures, and concrete
 * implementations (see [MetricParameter], [DimensionOrEntityParameter],
 * [TimeDimensionParameter], [OrderByParameter], [SavedQueryParameter])
 * provide the resolver-input factory.
 */

/** A metric requested in a query. Port of `MetricQueryParameter`. */
interface MetricQueryParameter {
    val name: String
    val alias: String?
    fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForMetric
}

/**
 * A generic group-by parameter — may resolve to either a dimension or an
 * entity.
 *
 * Port of `DimensionOrEntityQueryParameter`.
 */
interface DimensionOrEntityQueryParameter : GroupByQueryParameter {
    val name: String
    val alias: String?
    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForGroupByItem
}

/** A time-dimension parameter. Port of `TimeDimensionQueryParameter`. */
interface TimeDimensionQueryParameter : GroupByQueryParameter {
    val name: String
    val grain: String?
    val datePart: DatePart?
    val alias: String?
    override fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForGroupByItem
}

/**
 * The union of [DimensionOrEntityQueryParameter] and [TimeDimensionQueryParameter].
 *
 * Port of Python's `GroupByQueryParameter = Union[...]`. Kotlin uses a
 * sealed-friendly interface so the order-by accepts either kind.
 */
interface GroupByQueryParameter {
    fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForGroupByItem
}

/** Order-by parameter combining a target with a direction. Port of `OrderByQueryParameter`. */
interface OrderByQueryParameter {
    /** Either a [MetricQueryParameter] or a [GroupByQueryParameter]. */
    val orderBy: Any
    val descending: Boolean
    fun queryResolverInput(semanticManifestLookup: SemanticManifestLookup): ResolverInputForOrderByItem
}

/** Reference to a saved query by name. Port of `SavedQueryParameter`. */
interface SavedQueryParameter {
    val name: String
}
