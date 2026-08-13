package cc.monomer.metricflow.domain.manifest.model.element

import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import kotlinx.serialization.Serializable

/**
 * A join-key declaration inside a semantic model.
 *
 * Port of `metricflow_semantic_interfaces/implementations/elements/entity.py::PydanticEntity`.
 */
@Serializable
data class Entity(
    val name: String,
    val description: String? = null,
    val type: EntityType,
    val role: String? = null,
    val expr: String? = null,
    val metadata: Metadata? = null,
    val label: String? = null,
    val config: SemanticLayerElementConfig? = null,
) {
    val reference: EntityReference get() = EntityReference(name)

    /** Whether this entity type can serve as a link in a join chain. */
    val isLinkableEntityType: Boolean
        get() = type == EntityType.PRIMARY || type == EntityType.UNIQUE || type == EntityType.NATURAL
}
