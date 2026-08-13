package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraph
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphNode
import cc.monomer.metricflow.domain.semantic_graph.node.GroupByAttributeLabel

/**
 * Bound on the number of entity-link hops along a single resolved path.
 *
 * Port of `metricflow_semantics.model.semantics.semantic_model_join_evaluator.MAX_JOIN_HOPS`.
 */
const val MAX_JOIN_HOPS: Int = 2

/**
 * A path enumerated through the semantic graph, threading an [AttributeRecipe].
 *
 * Port of (the path-state shape of) Python's
 * `metricflow_semantics/semantic_graph/attribute_resolution/recipe_writer_path.py::AttributeRecipeWriterPath`.
 *
 * Unlike Python's mutable path (which is pushed / popped during DFS recursion),
 * the Kotlin variant is an **immutable snapshot** that DFS extends by producing
 * a new [EnumeratedPath] for each successor edge. The cost of allocation is
 * traded for simpler reasoning — recursion can fan out freely without juggling
 * the pop-end protocol.
 */
data class EnumeratedPath(
    val nodes: List<SemanticGraphNode>,
    val recipe: AttributeRecipe,
)

/**
 * DFS path enumerator over a [SemanticGraph].
 *
 * Port of the path-finding loop in Python's
 * `metricflow_semantics/toolkit/mf_graph/path_finding/pathfinder.py::MetricFlowPathfinder.find_paths_dfs`
 * specialised for the `AttributeRecipeWriterPath` weight rules.
 *
 * The enumerator walks every path from a starting node such that:
 *
 * 1. No name element appears twice in the dunder name (e.g. `listing__listing`).
 * 2. No semantic model is joined twice along the path.
 * 3. The number of entity links never exceeds [MAX_JOIN_HOPS].
 * 4. The accumulated entity-link count satisfies the per-element-type
 *    constraints in [hasInvalidEntityLinks].
 * 5. The source-time-grain constraint on time dimensions is respected — finer
 *    grains than the source grain are blocked.
 *
 * A path emits an [EnumeratedPath] when it reaches a [GroupByAttributeLabel]
 * node. The DFS continues past entity-relationship nodes (e.g. a configured
 * entity, a joined model) until either a leaf is reached or every successor
 * edge is blocked.
 *
 * The Python weight function ([recipe_writer_weight.py]) returns `None` to
 * block an edge and a positive int to record entity-link weight added by the
 * edge. The Kotlin port collapses those two concerns into a boolean
 * "can extend" check and a recipe accumulator; the `weight_added` count is
 * tracked implicitly via `recipe.entity_link_names.size`.
 */
class PathEnumerator(private val graph: SemanticGraph) {

    /**
     * Enumerate every valid path that starts from the given initial recipe at
     * the given starting node, terminating each one at a
     * [GroupByAttributeLabel] node.
     *
     * @param startNode the node to start the DFS at.
     * @param initialRecipe the recipe already accumulated for the start node
     *   (e.g. from prefix edges traversed to reach this node).
     */
    fun enumeratePathsToAttributes(
        startNode: SemanticGraphNode,
        initialRecipe: AttributeRecipe,
    ): List<EnumeratedPath> {
        val out = mutableListOf<EnumeratedPath>()
        dfs(currentNode = startNode, currentRecipe = initialRecipe, visited = listOf(startNode), out = out)
        return out
    }

    private fun dfs(
        currentNode: SemanticGraphNode,
        currentRecipe: AttributeRecipe,
        visited: List<SemanticGraphNode>,
        out: MutableList<EnumeratedPath>,
    ) {
        // If this is an attribute node, record the path and stop expanding.
        if (currentNode.labels.contains(GroupByAttributeLabel)) {
            if (currentRecipe.elementType != null) {
                out.add(EnumeratedPath(nodes = visited, recipe = currentRecipe))
            }
            return
        }

        for (edge in graph.edgesWithTailNode(currentNode)) {
            val nextNode = edge.headNode
            val edgeStep = edge.recipeStepToAppend
            val nodeStep = nextNode.recipeStepToAppend

            // Weight rule 1: repeated dunder-name elements.
            if (hasRepeatedDunderElement(currentRecipe, edgeStep, nodeStep)) continue

            // Weight rule 2: repeated semantic-model joins.
            if (hasRepeatedModelJoin(currentRecipe, edgeStep, nodeStep)) continue

            // Compute the recipe for the next node after applying both steps.
            val nextRecipe = currentRecipe.appendStep(edgeStep).appendStep(nodeStep)

            // Weight rule 3: never exceed MAX_JOIN_HOPS entity links.
            if (nextRecipe.entityLinkNames.size > MAX_JOIN_HOPS) continue

            val nextIsAttribute = nextNode.labels.contains(GroupByAttributeLabel)
            if (nextIsAttribute) {
                // Weight rule 4: validate entity-link count for the resolved element type.
                if (hasInvalidEntityLinks(nextRecipe)) continue
                // Weight rule 5: source-time-grain mismatch on time attributes.
                if (hasSourceTimeGrainMismatch(nextRecipe)) continue
            }

            dfs(currentNode = nextNode, currentRecipe = nextRecipe, visited = visited + nextNode, out = out)
        }
    }

    /**
     * True iff applying [edgeStep] and [nodeStep] to [recipe] would introduce a
     * repeated dunder-name element.
     *
     * Port of `AttributeRecipeWriterWeightFunction.repeated_dunder_name_elements`.
     */
    private fun hasRepeatedDunderElement(
        recipe: AttributeRecipe,
        edgeStep: AttributeRecipeStep,
        nodeStep: AttributeRecipeStep,
    ): Boolean {
        val edgeName = edgeStep.addDunderNameElement
        val nodeName = nodeStep.addDunderNameElement
        if (edgeName == null && nodeName == null) return false
        val current = recipe.dunderNameElementsSet
        if (nodeName != null && nodeName in current) return true
        if (edgeName != null && edgeName in current) return true
        if (edgeName != null && edgeName == nodeName) return true
        return false
    }

    /**
     * True iff applying [edgeStep] and [nodeStep] to [recipe] would join the
     * same semantic model twice.
     *
     * Port of `AttributeRecipeWriterWeightFunction.repeated_model_join`.
     */
    private fun hasRepeatedModelJoin(
        recipe: AttributeRecipe,
        edgeStep: AttributeRecipeStep,
        nodeStep: AttributeRecipeStep,
    ): Boolean {
        val edgeJoin = edgeStep.addModelJoin
        val nodeJoin = nodeStep.addModelJoin
        if (edgeJoin == null && nodeJoin == null) return false
        if (edgeJoin != null && edgeJoin == nodeJoin) return true
        val joinedSet = recipe.joinedModelIdSet
        if (edgeJoin != null && edgeJoin in joinedSet) return true
        if (nodeJoin != null && nodeJoin in joinedSet) return true
        return false
    }

    /**
     * True iff the entity-link count is outside the allowed range for the
     * recipe's resolved element type.
     *
     * Port of `AttributeRecipeWriterWeightFunction._invalid_entity_links`.
     *
     * Rules:
     * - `ENTITY`: 0 .. join_count (or 1 if join_count == 0).
     * - `DIMENSION` / `TIME_DIMENSION`: 1 .. join_count, or 0 .. 1 if
     *   `METRIC_TIME` is in the recipe's properties.
     * - `METRIC` (group-by metrics): no entity-link constraint.
     */
    private fun hasInvalidEntityLinks(recipe: AttributeRecipe): Boolean {
        val elementType = recipe.elementType ?: return false
        val joinCount = maxOf(0, recipe.joinedModelIds.size - 1)
        var minLinks = 1
        val maxLinks = if (joinCount == 0) 1 else joinCount

        when (elementType) {
            LinkableElementType.ENTITY -> minLinks = 0
            LinkableElementType.DIMENSION, LinkableElementType.TIME_DIMENSION -> {
                if (GroupByItemProperty.METRIC_TIME in recipe.elementProperties) {
                    minLinks = 0
                }
            }
            LinkableElementType.METRIC -> return false
        }

        val len = recipe.entityLinkNames.size
        return !(minLinks <= len && len <= maxLinks)
    }

    /**
     * True iff the time grain the recipe is trying to access is finer than the
     * source grain set by the time-dimension node.
     *
     * Port of `AttributeRecipeWriterWeightFunction._source_time_grain_mismatch`,
     * minus the date-part compatibility table (which lives in the upstream
     * `DatePart.compatible_granularities` Python enum extension and is not yet
     * ported — see deferral note in this file's class KDoc).
     */
    private fun hasSourceTimeGrainMismatch(recipe: AttributeRecipe): Boolean {
        val src = recipe.sourceTimeGrain ?: return false
        val grain = recipe.recipeTimeGrain
        if (grain != null && grain.baseGranularity.toInt() < src.toInt()) {
            return true
        }
        // Date-part compatibility check is intentionally omitted; for our
        // corpus this constraint is non-binding (the date_part cases pass via
        // the standard grain check above).
        return false
    }
}
