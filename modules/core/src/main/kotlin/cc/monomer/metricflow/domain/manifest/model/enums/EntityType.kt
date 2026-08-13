package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Defines uniqueness and the extent to which an entity represents the common entity for a semantic model.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/entity_type.py::EntityType`.
 */
@Serializable
enum class EntityType(val value: String) {
    @SerialName("foreign") FOREIGN("foreign"),
    @SerialName("natural") NATURAL("natural"),
    @SerialName("primary") PRIMARY("primary"),
    @SerialName("unique") UNIQUE("unique"),
}
