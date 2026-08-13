package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.semantic_graph.EntityRelationshipEdge
import cc.monomer.metricflow.domain.semantic_graph.JoinedModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.MetricTimeNode
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.TimeDimensionNode
import cc.monomer.metricflow.domain.semantic_graph.TimeNode
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.lookup.ModelObjectLookup

/**
 * Generator for time-dimension entity nodes.
 *
 * Port of `TimeDimensionSubgraphGenerator`.
 */
class TimeDimensionSubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        for (lookup in manifestObjectLookup.modelObjectLookups) {
            addEdgesForModel(lookup, edgeList)
        }
        edgeList.add(
            EntityRelationshipEdge.create(
                tailNode = MetricTimeNode.getInstance(),
                headNode = TimeNode.getInstance(),
                recipeUpdate = AttributeRecipeStep.EMPTY,
            ),
        )
    }

    private fun addEdgesForModel(lookup: ModelObjectLookup, edgeList: MutableList<SemanticGraphEdge>) {
        val modelId = SemanticModelId.getInstance(lookup.semanticModel.name)
        val modelNode = JoinedModelNode.getInstance(modelId)

        for ((timeDimensionName, timeGrain) in lookup.timeDimensionNameToGrain) {
            val timeDimensionNode = TimeDimensionNode.getInstance(timeDimensionName)
            edgeList.add(
                EntityRelationshipEdge.create(
                    tailNode = modelNode,
                    headNode = timeDimensionNode,
                    recipeUpdate = AttributeRecipeStep.EMPTY.copy(setSourceTimeGrain = timeGrain),
                ),
            )
            edgeList.add(
                EntityRelationshipEdge.create(
                    tailNode = timeDimensionNode,
                    headNode = TimeNode.getInstance(),
                    recipeUpdate = AttributeRecipeStep.EMPTY,
                ),
            )
        }
    }
}
