package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.EntityAttributeEdge
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.TimeAttributeNode
import cc.monomer.metricflow.domain.semantic_graph.TimeNode
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep

/**
 * Generates the time-entity subgraph.
 *
 * Port of `TimeEntitySubgraphGenerator`. Emits edges from [TimeNode] to one
 * [TimeAttributeNode] per queryable grain / date_part / expanded grain. The
 * minimum queryable grain is the minimum of:
 *
 * - the smallest grain in the time spine
 * - the smallest grain used by any time dimension in the manifest.
 *
 * When no spine is configured, model-owned time dimensions still determine the queryable grains.
 * Synthetic `metric_time` use remains guarded at the engine query boundary.
 */
class TimeEntitySubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        val minTimeGrain = listOfNotNull(
            manifestObjectLookup.minTimeGrainInTimeSpine,
            manifestObjectLookup.minTimeGrainUsedInModels,
        ).minByOrNull { it.toInt() } ?: return
        addEdgesForTimeEntitySubgraph(minTimeGrain, edgeList)
    }

    private fun addEdgesForTimeEntitySubgraph(
        minTimeGrain: TimeGranularity,
        edgeList: MutableList<SemanticGraphEdge>,
    ) {
        val timeEntity = TimeNode.getInstance()
        val queryableGrains = TimeGranularity.entries.filter { it.toInt() >= minTimeGrain.toInt() }

        for (grain in queryableGrains) {
            edgeList.add(
                EntityAttributeEdge.create(
                    tailNode = timeEntity,
                    headNode = TimeAttributeNode.getInstanceForTimeGrain(grain),
                    recipeStep = AttributeRecipeStep.EMPTY.copy(
                        setTimeGrainAccess = ExpandedTimeGranularity(grain.value, grain),
                    ),
                ),
            )
        }

        val applicableDateParts = DatePart.entries.filter { it.toInt() >= minTimeGrain.toInt() }
        for (datePart in applicableDateParts) {
            edgeList.add(
                EntityAttributeEdge.create(
                    tailNode = timeEntity,
                    headNode = TimeAttributeNode.getInstanceForDatePart(datePart),
                    recipeStep = AttributeRecipeStep.EMPTY.copy(
                        addProperties = listOf(GroupByItemProperty.DATE_PART),
                        setDatePartAccess = datePart,
                    ),
                ),
            )
        }

        for (expanded in manifestObjectLookup.expandedTimeGrains) {
            if (expanded.baseGranularity.toInt() >= minTimeGrain.toInt()) {
                edgeList.add(
                    EntityAttributeEdge.create(
                        tailNode = timeEntity,
                        headNode = TimeAttributeNode.getInstanceForExpandedTimeGrain(expanded),
                        recipeStep = AttributeRecipeStep.EMPTY.copy(
                            addProperties = listOf(GroupByItemProperty.DERIVED_TIME_GRANULARITY),
                            setTimeGrainAccess = expanded,
                        ),
                    ),
                )
            }
        }
    }
}
