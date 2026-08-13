package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.semantic_graph.EntityAttributeEdge
import cc.monomer.metricflow.domain.semantic_graph.JoinedModelNode
import cc.monomer.metricflow.domain.semantic_graph.KeyAttributeNode
import cc.monomer.metricflow.domain.semantic_graph.LocalModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.lookup.ModelObjectLookup

/**
 * Generator that adds edges from each model node to its entity-key attribute nodes.
 *
 * Port of `EntityKeySubgraphGenerator`. Adds edges from both the
 * [JoinedModelNode] and [LocalModelNode] to each [KeyAttributeNode].
 */
class EntityKeySubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        for (lookup in manifestObjectLookup.modelObjectLookups) {
            addEdgesForModel(lookup, edgeList)
        }
    }

    private fun addEdgesForModel(lookup: ModelObjectLookup, edgeList: MutableList<SemanticGraphEdge>) {
        val modelId = SemanticModelId.getInstance(lookup.semanticModel.name)
        val joined = JoinedModelNode.getInstance(modelId)
        val local = LocalModelNode.getInstance(modelId)
        val keyNodes = lookup.semanticModel.entities.map { KeyAttributeNode.getInstance(it.name) }

        for (key in keyNodes) {
            edgeList.add(EntityAttributeEdge.create(tailNode = joined, headNode = key, recipeStep = null))
        }
        for (key in keyNodes) {
            edgeList.add(EntityAttributeEdge.create(tailNode = local, headNode = key, recipeStep = null))
        }
    }
}
