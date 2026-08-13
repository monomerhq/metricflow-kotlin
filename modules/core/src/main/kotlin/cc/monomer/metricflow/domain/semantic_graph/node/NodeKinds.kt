package cc.monomer.metricflow.domain.semantic_graph.node

/**
 * Kind tag for [cc.monomer.metricflow.domain.semantic_graph.TimeAttributeNode].
 *
 * Python ships three named factories — `get_instance_for_time_grain`,
 * `get_instance_for_date_part`, `get_instance_for_expanded_time_grain`. The
 * Kotlin port carries the tag explicitly so callers can branch on the
 * underlying construction style.
 */
enum class TimeAttributeNodeKind {
    STANDARD_GRAIN,
    DATE_PART,
    EXPANDED_GRAIN,
}

/** Kind tag for [cc.monomer.metricflow.domain.semantic_graph.KeyAttributeNode]. */
enum class KeyAttributeNodeKind {
    ENTITY_KEY,
}

/** Kind tag for [cc.monomer.metricflow.domain.semantic_graph.CategoricalDimensionAttributeNode]. */
enum class CategoricalDimensionAttributeNodeKind {
    CATEGORICAL,
}
