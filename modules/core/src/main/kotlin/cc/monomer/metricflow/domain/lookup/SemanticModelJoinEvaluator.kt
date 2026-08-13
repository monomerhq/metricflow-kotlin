package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/** Maximum number of joins evaluated when chaining hops between two semantic models. */
const val MAX_JOIN_HOPS: Int = 2

/**
 * Describes the type of a single join between two semantic models in terms of the entity types
 * on each side.
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_join_evaluator.py::SemanticModelEntityJoinType`.
 */
data class SemanticModelEntityJoinType(
    val leftEntityType: EntityType,
    val rightEntityType: EntityType,
)

/**
 * How to join one semantic model onto another, using a specific entity and join type.
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_join_evaluator.py::SemanticModelEntityJoin`.
 */
data class SemanticModelEntityJoin(
    val rightSemanticModelReference: SemanticModelReference,
    val entityReference: EntityReference,
    val joinType: SemanticModelEntityJoinType,
)

/**
 * The valid join path to link two semantic models. May include multiple hops.
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_join_evaluator.py::SemanticModelLink`.
 */
data class SemanticModelLink(
    val leftSemanticModelReference: SemanticModelReference,
    val joinPath: List<SemanticModelEntityJoin>,
)

/**
 * Checks whether a join between two semantic models should be allowed.
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_join_evaluator.py::SemanticModelJoinEvaluator`.
 *
 * The set of valid and invalid entity-type pairs is identical to Python. Validity also depends on
 * whether the involved models declare validity dimensions (SCD Type II) — these models cannot
 * be joined together due to fanout concerns.
 */
class SemanticModelJoinEvaluator(
    private val semanticModelLookup: SemanticModelLookup,
) {

    /**
     * Get the valid join type used to join semantic models on the given entity, or `null` if no
     * valid join exists.
     */
    fun getValidSemanticModelEntityJoinType(
        leftSemanticModelReference: SemanticModelReference,
        rightSemanticModelReference: SemanticModelReference,
        onEntityReference: EntityReference,
    ): SemanticModelEntityJoinType? {
        val leftEntity = semanticModelLookup.getEntityInSemanticModel(
            SemanticModelElementReference.createFromReferences(leftSemanticModelReference, onEntityReference),
        )
        val rightEntity = semanticModelLookup.getEntityInSemanticModel(
            SemanticModelElementReference.createFromReferences(rightSemanticModelReference, onEntityReference),
        )
        if (leftEntity == null || rightEntity == null) return null

        val leftSemanticModel = semanticModelLookup.getByReference(leftSemanticModelReference)
        val rightSemanticModel = semanticModelLookup.getByReference(rightSemanticModelReference)
        checkNotNull(leftSemanticModel) { "Type refinement. If you see this error something has refactored wrongly" }
        checkNotNull(rightSemanticModel) { "Type refinement. If you see this error something has refactored wrongly" }

        if (leftSemanticModel.hasValidityDimensions && rightSemanticModel.hasValidityDimensions) {
            // Cannot join two semantic models with validity dimensions due to fanout concerns.
            return null
        }

        if (rightEntity.type == EntityType.NATURAL) {
            if (!rightSemanticModel.hasValidityDimensions) {
                // No way to refine a NATURAL entity to a single row per key without validity dims.
                return null
            }
        }

        val joinType = SemanticModelEntityJoinType(leftEntity.type, rightEntity.type)
        return when (joinType) {
            in VALID_ENTITY_JOINS -> joinType
            in INVALID_ENTITY_JOINS -> null
            else -> throw IllegalStateException("Join type not handled: $joinType")
        }
    }

    /** Return `true` iff a join with the given parameters is allowed when resolving a query. */
    fun isValidSemanticModelJoin(
        leftSemanticModelReference: SemanticModelReference,
        rightSemanticModelReference: SemanticModelReference,
        onEntityReference: EntityReference,
    ): Boolean = getValidSemanticModelEntityJoinType(
        leftSemanticModelReference = leftSemanticModelReference,
        rightSemanticModelReference = rightSemanticModelReference,
        onEntityReference = onEntityReference,
    ) != null

    companion object {
        /** Non-fanout joins — these are the joins MetricFlow allows. */
        val VALID_ENTITY_JOINS: List<SemanticModelEntityJoinType> = listOf(
            SemanticModelEntityJoinType(EntityType.PRIMARY, EntityType.NATURAL),
            SemanticModelEntityJoinType(EntityType.PRIMARY, EntityType.PRIMARY),
            SemanticModelEntityJoinType(EntityType.PRIMARY, EntityType.UNIQUE),
            SemanticModelEntityJoinType(EntityType.UNIQUE, EntityType.NATURAL),
            SemanticModelEntityJoinType(EntityType.UNIQUE, EntityType.PRIMARY),
            SemanticModelEntityJoinType(EntityType.UNIQUE, EntityType.UNIQUE),
            SemanticModelEntityJoinType(EntityType.FOREIGN, EntityType.NATURAL),
            SemanticModelEntityJoinType(EntityType.FOREIGN, EntityType.PRIMARY),
            SemanticModelEntityJoinType(EntityType.FOREIGN, EntityType.UNIQUE),
            SemanticModelEntityJoinType(EntityType.NATURAL, EntityType.PRIMARY),
            SemanticModelEntityJoinType(EntityType.NATURAL, EntityType.UNIQUE),
        )

        /** Joins disallowed because they would introduce fanout or undefined-cardinality issues. */
        val INVALID_ENTITY_JOINS: List<SemanticModelEntityJoinType> = listOf(
            SemanticModelEntityJoinType(EntityType.PRIMARY, EntityType.FOREIGN),
            SemanticModelEntityJoinType(EntityType.UNIQUE, EntityType.FOREIGN),
            SemanticModelEntityJoinType(EntityType.FOREIGN, EntityType.FOREIGN),
            SemanticModelEntityJoinType(EntityType.NATURAL, EntityType.FOREIGN),
            // NATURAL → NATURAL not allowed due to hidden fanout or missing-value concerns when
            // multiple validity windows are in play.
            SemanticModelEntityJoinType(EntityType.NATURAL, EntityType.NATURAL),
        )
    }
}
