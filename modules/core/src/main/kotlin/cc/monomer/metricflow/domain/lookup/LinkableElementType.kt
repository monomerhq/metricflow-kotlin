package cc.monomer.metricflow.domain.lookup

/**
 * Enumeration of the possible types of linkable element we are encountering or expecting.
 *
 * Port of `metricflow_semantics/model/semantics/linkable_element.py::LinkableElementType`.
 *
 * Group-by items effectively map onto `LinkableSpec`s and queryable semantic-manifest elements
 * such as metrics, dimensions, and entities. This provides the full set of types we might
 * encounter, and is useful for ensuring that we are always getting the correct linkable
 * element from a given part of the codebase (e.g., to ensure we are not accidentally getting
 * an [ENTITY] when we expect a [DIMENSION]).
 *
 * Python's `OrderedEnum` ordering is unnecessary in Kotlin because [enum class] already
 * supports `compareTo` based on declaration order.
 */
enum class LinkableElementType(val value: String) {
    DIMENSION("dimension"),
    ENTITY("entity"),
    METRIC("metric"),
    TIME_DIMENSION("time_dimension"),
}
