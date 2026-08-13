package cc.monomer.metricflow.domain.dataflow.validation

import cc.monomer.metricflow.domain.dataflow.instance.EntityInstance
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.lookup.SemanticModelJoinEvaluator
import cc.monomer.metricflow.domain.lookup.SemanticModelLookup
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Validates that the [InstanceSet] produced by a join dataflow node is a valid join.
 *
 * Port of `metricflow.validation.dataflow_join_validator.JoinDataflowOutputValidator`.
 *
 * The Python module lives under `metricflow/validation/`; the Kotlin port co-locates it
 * with the [InstanceSet] types in `:domain:dataflow` since it is logically a check applied to
 * dataflow-layer values and would otherwise force `:domain:lookup` to depend on
 * `:domain:dataflow` (creating a cycle — `:domain:dataflow` already depends on
 * `:domain:lookup`). The validator delegates the actual entity-join-compatibility logic to
 * [SemanticModelJoinEvaluator].
 */
class JoinDataflowOutputValidator(
    private val semanticModelLookup: SemanticModelLookup,
) {

    private val joinEvaluator: SemanticModelJoinEvaluator = SemanticModelJoinEvaluator(semanticModelLookup)

    /**
     * Return `true` iff the supplied instance sets can be joined on [onEntityReference].
     *
     * When [rightNodeIsAggregatedToEntity] is `true`, the right side is treated as
     * pre-aggregated to the join entity and the usual fanout check on the right model is
     * skipped; we only need to confirm that the left side carries the same entity at the
     * join level.
     */
    fun isValidInstanceSetJoin(
        leftInstanceSet: InstanceSet,
        rightInstanceSet: InstanceSet,
        onEntityReference: EntityReference,
        rightNodeIsAggregatedToEntity: Boolean,
    ): Boolean {
        val leftSemanticModelReference = semanticModelOfEntityInInstanceSet(
            instanceSet = leftInstanceSet,
            entityReference = onEntityReference,
        )
        if (rightNodeIsAggregatedToEntity) {
            // The left entity must exist on the left semantic model so the join key is
            // well-defined.
            val leftEntity = semanticModelLookup.getEntityInSemanticModel(
                SemanticModelElementReference.createFromReferences(leftSemanticModelReference, onEntityReference),
            ) ?: return false
            @Suppress("UNUSED_VARIABLE") val unused = leftEntity

            val possibleRightEntities = rightInstanceSet.entityInstances
                .filter { it.spec.reference == onEntityReference }
            if (possibleRightEntities.size != 1) return false

            // No fanout check needed since the right subquery is aggregated to the entity
            // level, ensuring uniqueness.
            return true
        }

        return joinEvaluator.isValidSemanticModelJoin(
            leftSemanticModelReference = leftSemanticModelReference,
            rightSemanticModelReference = semanticModelOfEntityInInstanceSet(
                instanceSet = rightInstanceSet,
                entityReference = onEntityReference,
            ),
            onEntityReference = onEntityReference,
        )
    }

    /**
     * Default overload: assume the right node is not pre-aggregated to the entity.
     *
     * Mirrors the Python default of `right_node_is_aggregated_to_entity=False`.
     */
    fun isValidInstanceSetJoin(
        leftInstanceSet: InstanceSet,
        rightInstanceSet: InstanceSet,
        onEntityReference: EntityReference,
    ): Boolean = isValidInstanceSetJoin(
        leftInstanceSet = leftInstanceSet,
        rightInstanceSet = rightInstanceSet,
        onEntityReference = onEntityReference,
        rightNodeIsAggregatedToEntity = false,
    )

    companion object {
        /**
         * Return the [SemanticModelReference] that defines [entityReference] within the supplied
         * [instanceSet]. The instance must be present at the local (zero entity-links) level.
         */
        @JvmStatic
        fun semanticModelOfEntityInInstanceSet(
            instanceSet: InstanceSet,
            entityReference: EntityReference,
        ): SemanticModelReference {
            val matching = mutableListOf<EntityInstance>()
            for (instance in instanceSet.entityInstances) {
                check(instance.definedFrom.size == 1) {
                    "Expected exactly one defined_from entry on entity instance, got: ${instance.definedFrom}"
                }
                if (instance.spec.entityLinks.isEmpty() && instance.spec.reference == entityReference) {
                    matching.add(instance)
                }
            }
            check(matching.size == 1) {
                "Not exactly 1 matching entity instances found: $matching for $entityReference in $instanceSet"
            }
            return matching[0].originSemanticModelReference.semanticModelReference
        }
    }
}
