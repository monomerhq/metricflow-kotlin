package cc.monomer.metricflow.domain.semantic_graph.lookup

import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId

/**
 * Describes a join between two entities based on their types.
 *
 * Port of `EntityJoinType` in `metricflow_semantics/semantic_graph/lookups/join_lookup.py`.
 */
data class EntityJoinType(
    val leftEntityType: EntityType,
    val rightEntityType: EntityType,
)

/**
 * Describes one possible join of a model on the right side of another.
 *
 * Port of `JoinModelOnRightDescriptor`.
 */
data class JoinModelOnRightDescriptor(
    val entityName: String,
    val joinType: EntityJoinType,
)

/**
 * Lookup that enumerates valid joins between semantic models.
 *
 * Port of `metricflow_semantics/semantic_graph/lookups/join_lookup.py::SemanticModelJoinLookup`.
 *
 * Maintains static tables of valid / invalid entity-type pairs and uses them
 * (plus validity-dimension constraints) to compute the joinable right-side
 * models per left model.
 */
class SemanticModelJoinLookup(manifestObjectLookup: ManifestObjectLookup) {

    private val modelIdToLookup = manifestObjectLookup.modelIdToLookup
    private val entityNameToModelIds = manifestObjectLookup.entityNameToModelIds

    private val modelIdToHasValidityDimensions: Map<SemanticModelId, Boolean> =
        modelIdToLookup.mapValues { (_, lookup) -> lookup.semanticModel.hasValidityDimensions }

    /** Entity types that can appear on the right side of a valid join. */
    val validJoinToEntityTypes: OrderedSet<EntityType> = FrozenOrderedSet(VALID_ENTITY_JOINS.map { it.rightEntityType })

    /**
     * Return descriptors for semantic models that can be joined to [leftModelId].
     */
    fun getJoinModelOnRightDescriptors(
        leftModelId: SemanticModelId,
    ): Map<SemanticModelId, OrderedSet<JoinModelOnRightDescriptor>> {
        val rightModelIdToDescriptors = LinkedHashMap<SemanticModelId, MutableOrderedSet<JoinModelOnRightDescriptor>>()
        val leftLookup = modelIdToLookup[leftModelId]
            ?: throw NoSuchElementException("Unknown semantic model id: $leftModelId")
        val leftHasValidity = modelIdToHasValidityDimensions.getValue(leftModelId)

        for (entity in leftLookup.semanticModel.entities) {
            val leftEntityName = entity.name
            val leftEntityType = entity.type
            val otherModelIds = entityNameToModelIds[leftEntityName] ?: continue
            for (rightModelId in otherModelIds) {
                val rightLookup = modelIdToLookup.getValue(rightModelId)
                val rightEntityType = rightLookup.entityLookup.entityNameToType[leftEntityName] ?: continue
                val rightHasValidity = modelIdToHasValidityDimensions.getValue(rightModelId)

                val joinType = EntityJoinType(leftEntityType, rightEntityType)

                when {
                    joinType in VALID_ENTITY_JOINS -> Unit
                    joinType in INVALID_ENTITY_JOINS -> continue
                    else -> throw IllegalStateException(
                        "Unknown join type. join_type=$joinType " +
                            "left_entity=$leftEntityName left_model=${leftLookup.semanticModel.name} " +
                            "right_model=${rightLookup.semanticModel.name}",
                    )
                }

                if (leftHasValidity && rightHasValidity) continue
                if (rightEntityType == EntityType.NATURAL && !rightHasValidity) continue

                rightModelIdToDescriptors.getOrPut(rightModelId) { MutableOrderedSet() }
                    .add(JoinModelOnRightDescriptor(leftEntityName, joinType))
            }
        }
        return rightModelIdToDescriptors.mapValues { FrozenOrderedSet(it.value) }
    }

    companion object {
        private val VALID_ENTITY_JOINS: Set<EntityJoinType> = setOf(
            EntityJoinType(EntityType.PRIMARY, EntityType.NATURAL),
            EntityJoinType(EntityType.PRIMARY, EntityType.PRIMARY),
            EntityJoinType(EntityType.PRIMARY, EntityType.UNIQUE),
            EntityJoinType(EntityType.UNIQUE, EntityType.NATURAL),
            EntityJoinType(EntityType.UNIQUE, EntityType.PRIMARY),
            EntityJoinType(EntityType.UNIQUE, EntityType.UNIQUE),
            EntityJoinType(EntityType.FOREIGN, EntityType.NATURAL),
            EntityJoinType(EntityType.FOREIGN, EntityType.PRIMARY),
            EntityJoinType(EntityType.FOREIGN, EntityType.UNIQUE),
            EntityJoinType(EntityType.NATURAL, EntityType.PRIMARY),
            EntityJoinType(EntityType.NATURAL, EntityType.UNIQUE),
        )

        private val INVALID_ENTITY_JOINS: Set<EntityJoinType> = setOf(
            EntityJoinType(EntityType.PRIMARY, EntityType.FOREIGN),
            EntityJoinType(EntityType.UNIQUE, EntityType.FOREIGN),
            EntityJoinType(EntityType.FOREIGN, EntityType.FOREIGN),
            EntityJoinType(EntityType.NATURAL, EntityType.FOREIGN),
            EntityJoinType(EntityType.NATURAL, EntityType.NATURAL),
        )
    }
}
