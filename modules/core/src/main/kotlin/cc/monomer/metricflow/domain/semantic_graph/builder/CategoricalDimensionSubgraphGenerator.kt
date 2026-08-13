package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.semantic_graph.AttributeNode
import cc.monomer.metricflow.domain.semantic_graph.CategoricalDimensionAttributeNode
import cc.monomer.metricflow.domain.semantic_graph.EntityAttributeEdge
import cc.monomer.metricflow.domain.semantic_graph.JoinedModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.lookup.ModelObjectLookup

/**
 * Generator that adds edges from joined-model nodes to their categorical
 * dimension attribute nodes.
 *
 * Port of `CategoricalDimensionSubgraphGenerator`.
 */
class CategoricalDimensionSubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        for (lookup in manifestObjectLookup.modelObjectLookups) {
            addEdgesForModel(lookup, edgeList)
        }
    }

    private fun categoricalAttributeNodesFor(lookup: ModelObjectLookup): List<AttributeNode> {
        val nodes = mutableListOf<AttributeNode>()
        for (dimension in lookup.semanticModel.dimensions) {
            when (dimension.type) {
                DimensionType.CATEGORICAL -> nodes.add(CategoricalDimensionAttributeNode.getInstance(dimension.name))
                DimensionType.TIME -> Unit
            }
        }
        return nodes
    }

    private fun addEdgesForModel(lookup: ModelObjectLookup, edgeList: MutableList<SemanticGraphEdge>) {
        val modelId = SemanticModelId.getInstance(lookup.semanticModel.name)
        val tail = JoinedModelNode.getInstance(modelId)
        for (attr in categoricalAttributeNodesFor(lookup)) {
            edgeList.add(
                EntityAttributeEdge.create(
                    tailNode = tail,
                    headNode = attr,
                    recipeStep = null,
                ),
            )
        }
    }
}
