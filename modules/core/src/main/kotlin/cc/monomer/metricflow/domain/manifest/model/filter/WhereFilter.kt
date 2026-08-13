package cc.monomer.metricflow.domain.manifest.model.filter

import kotlinx.serialization.Serializable

/**
 * A templated SQL where-expression. Templates may extract dimensions, entities, measures,
 * and metrics referenced in the filter (the Jinja `Dimension(...)` / `Entity(...)` calls).
 *
 * Port of `metricflow_semantic_interfaces/implementations/filters/where_filter.py::PydanticWhereFilter`.
 *
 * The Python `_from_yaml_value` allowed a bare string to coerce into `PydanticWhereFilter(where_sql_template=input)`;
 * Kotlin parsing strictly uses the object form. (YAML-shaped input goes through a dedicated
 * pre-processor in a later wave, not through `kotlinx-serialization`.)
 */
@Serializable
data class WhereFilter(
    val whereSqlTemplate: String,
)

/**
 * A list of [WhereFilter]s logically AND-ed together to form a single filter clause.
 *
 * Port of `PydanticWhereFilterIntersection`.
 *
 * In Python this type supports several legacy input shapes (bare string, single object, list of
 * strings, etc.) via `__get_validators__`. The Kotlin port accepts only the canonical
 * `{"where_filters": [...]}` JSON shape; legacy coercion is handled at the parsing layer when we
 * port the YAML loader (Phase 3 W2 or later).
 */
@Serializable
data class WhereFilterIntersection(
    val whereFilters: List<WhereFilter>,
)
