package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import kotlinx.serialization.Serializable

/**
 * Parameters for a saved query — metrics, group-bys, optional ordering / limit / where.
 *
 * Port of `metricflow_semantic_interfaces/implementations/saved_query.py::PydanticSavedQueryQueryParams`.
 */
@Serializable
data class SavedQueryQueryParams(
    val metrics: List<String>,
    val groupBy: List<String> = emptyList(),
    val orderBy: List<String> = emptyList(),
    val limit: Int? = null,
    val where: WhereFilterIntersection? = null,
)

/**
 * A pre-named query (metrics + group-bys + optional ordering / limit / where + exports + tags).
 *
 * Port of `PydanticSavedQuery`.
 *
 * Python's `tags` field accepts either a `str` or `List[str]` for backwards-compatibility, and
 * sorts the result. In Kotlin the JSON corpus is always serialised as `List[str]`, so we keep
 * the canonical shape; YAML-side coercion belongs to a later parsing wave.
 */
@Serializable
data class SavedQuery(
    val name: String,
    val queryParams: SavedQueryQueryParams,
    val description: String? = null,
    val metadata: Metadata? = null,
    val label: String? = null,
    val exports: List<Export> = emptyList(),
    val tags: List<String> = emptyList(),
)
