package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.semantic_graph.ConfiguredEntityNode
import cc.monomer.metricflow.domain.semantic_graph.EntityRelationshipEdge
import cc.monomer.metricflow.domain.semantic_graph.JoinToModelEdge
import cc.monomer.metricflow.domain.semantic_graph.JoinedModelNode
import cc.monomer.metricflow.domain.semantic_graph.LocalModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.lookup.ModelObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.lookup.SemanticModelJoinLookup

/**
 * Generates the subgraph that represents joins between semantic models.
 *
 * Port of `EntityJoinSubgraphGenerator`.
 *
 * Joins are modelled as a path
 *
 *     LeftModelNode -> ConfiguredEntityNode -> RightModelNode
 *
 * Two flavours of "model node" exist — [JoinedModelNode] and [LocalModelNode]
 * — see those types for the rationale.
 */
class EntityJoinSubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    private val joinLookup: SemanticModelJoinLookup = SemanticModelJoinLookup(manifestObjectLookup)

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        for (lookup in manifestObjectLookup.modelObjectLookups) {
            addEdgesForModel(lookup, edgeList)
        }
    }

    private fun addEdgesForModel(lookup: ModelObjectLookup, edgeList: MutableList<SemanticGraphEdge>) {
        val leftModelId = SemanticModelId.getInstance(lookup.semanticModel.name)
        val leftJoined = JoinedModelNode.getInstance(leftModelId)
        val leftLocal = LocalModelNode.getInstance(leftModelId)
        val leftModel = lookup.semanticModel

        // Outgoing model-to-configured-entity edges.
        for ((rightModelId, joinDescriptors) in joinLookup.getJoinModelOnRightDescriptors(leftModelId)) {
            if (rightModelId == leftModelId) continue
            for (descriptor in joinDescriptors) {
                val rightEntityNode = ConfiguredEntityNode.getInstance(
                    entityName = descriptor.entityName,
                    modelId = rightModelId,
                )
                edgeList.add(
                    JoinToModelEdge.create(
                        tailNode = leftJoined,
                        headNode = rightEntityNode,
                        rightModelId = rightModelId,
                    ),
                )
                edgeList.add(
                    JoinToModelEdge.create(
                        tailNode = leftLocal,
                        headNode = rightEntityNode,
                        rightModelId = rightModelId,
                    ),
                )
            }
        }

        // Reverse: configured-entity -> joined-model, local-model -> configured-entity.
        val validRightTypes = joinLookup.validJoinToEntityTypes
        for (entity in leftModel.entities) {
            if (entity.type !in validRightTypes) continue
            val entityNode = ConfiguredEntityNode.getInstance(entity.name, leftModelId)
            edgeList.add(EntityRelationshipEdge.create(entityNode, leftJoined, AttributeRecipeStep.EMPTY))
            edgeList.add(EntityRelationshipEdge.create(leftLocal, entityNode, AttributeRecipeStep.EMPTY))
        }

        // Virtual primary entities.
        val primaryEntityName = lookup.semanticModel.primaryEntity
        if (primaryEntityName != null && lookup.semanticModel.entities.none { it.name == primaryEntityName }) {
            val primaryNode = ConfiguredEntityNode.getInstance(primaryEntityName, leftModelId)
            edgeList.add(EntityRelationshipEdge.create(leftLocal, primaryNode, AttributeRecipeStep.EMPTY))
            edgeList.add(EntityRelationshipEdge.create(primaryNode, leftJoined, AttributeRecipeStep.EMPTY))
        }
    }
}
