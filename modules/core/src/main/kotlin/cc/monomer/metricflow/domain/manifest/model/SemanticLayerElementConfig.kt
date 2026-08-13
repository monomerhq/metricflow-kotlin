package cc.monomer.metricflow.domain.manifest.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Free-form `meta` configuration attached to any semantic-layer element (metric, dimension,
 * entity, measure, semantic model, saved query).
 *
 * Port of `metricflow_semantic_interfaces/implementations/element_config.py::PydanticSemanticLayerElementConfig`.
 *
 * Python types this as `Dict[str, Any]`; we use `JsonObject` (a `Map<String, JsonElement>`)
 * to preserve heterogeneous user values verbatim across the round-trip without forcing a
 * Kotlin shape.
 */
@Serializable
data class SemanticLayerElementConfig(
    val meta: Map<String, JsonElement> = JsonObject(emptyMap()),
)
