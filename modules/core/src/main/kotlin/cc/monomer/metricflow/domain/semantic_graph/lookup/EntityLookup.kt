package cc.monomer.metricflow.domain.semantic_graph.lookup

import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType

/**
 * Per-model lookup over its entities.
 *
 * Port of `metricflow_semantics/semantic_graph/lookups/entity_lookup.py::EntityLookup`.
 */
class EntityLookup(entities: Iterable<Entity>) {

    private val entityNameToEntity: Map<String, Entity> = LinkedHashMap<String, Entity>().apply {
        for (e in entities) put(e.name, e)
    }

    /** Mapping from entity name to its type. */
    val entityNameToType: Map<String, EntityType> = entityNameToEntity.mapValues { it.value.type }

    /** Reverse mapping from entity type to the entity names of that type. */
    val entityTypeToNames: Map<EntityType, Set<String>> = entityNameToType.entries
        .groupBy({ it.value }, { it.key })
        .mapValues { (_, names) -> LinkedHashSet(names) }
}
