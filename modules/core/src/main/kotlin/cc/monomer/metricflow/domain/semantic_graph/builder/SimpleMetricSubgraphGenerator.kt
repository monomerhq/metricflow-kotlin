package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.semantic_graph.EntityRelationshipEdge
import cc.monomer.metricflow.domain.semantic_graph.LocalModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.MetricTimeNode
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.SimpleMetricNode
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.lookup.SimpleMetricModelObjectLookup

/**
 * Generator for the subgraph relating simple metrics to other entities.
 *
 * Port of `SimpleMetricSubgraphGenerator`.
 *
 * For each simple metric:
 *
 * - `SimpleMetricNode -> LocalModelNode`
 * - `SimpleMetricNode -> MetricTimeNode` (carrying the aggregation grain)
 */
class SimpleMetricSubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        for (lookup in manifestObjectLookup.simpleMetricModelLookups) {
            addEdgesForSimpleMetricModel(lookup, edgeList)
        }
    }

    private fun addEdgesForSimpleMetricModel(
        lookup: SimpleMetricModelObjectLookup,
        edgeList: MutableList<SemanticGraphEdge>,
    ) {
        val modelId = SemanticModelId.getInstance(lookup.semanticModel.name)
        val localModelNode = LocalModelNode.getInstance(modelId)
        val metricTimeNode = MetricTimeNode.getInstance()

        for ((aggregationConfiguration, simpleMetricInputs) in lookup.aggregationConfigurationToSimpleMetricInputs) {
            for (input in simpleMetricInputs) {
                val simpleMetricNode = SimpleMetricNode.getInstance(input.name)
                edgeList.add(
                    EntityRelationshipEdge.create(
                        tailNode = simpleMetricNode,
                        headNode = metricTimeNode,
                        recipeUpdate = AttributeRecipeStep.EMPTY.copy(
                            setSourceTimeGrain = aggregationConfiguration.timeGrain,
                            addModelJoin = modelId,
                        ),
                    ),
                )
                edgeList.add(
                    EntityRelationshipEdge.create(
                        tailNode = simpleMetricNode,
                        headNode = localModelNode,
                        recipeUpdate = AttributeRecipeStep.EMPTY,
                    ),
                )
            }
        }
    }
}
