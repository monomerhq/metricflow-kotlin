package cc.monomer.metricflow.domain.semantic_graph.pathfinder

import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.graph.PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS
import cc.monomer.metricflow.common.graph.Pathfinder
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraph
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphNode

/**
 * Pathfinder over the [SemanticGraph].
 *
 * Port of the semantic-graph-side wrapper around the toolkit
 * [cc.monomer.metricflow.common.graph.Pathfinder] (Python:
 * `MetricFlowPathfinder`). We reuse the toolkit BFS implementation
 * unchanged — Phase 3 W3 trimmed the Python DFS path-enumeration machinery
 * pending the deeper resolver port, so this wrapper exposes the BFS surface
 * the semantic-graph layer needs today.
 *
 * The Python `MetricFlowPathfinder` additionally supports per-edge weight
 * functions and DFS path enumeration. Those depend on the
 * [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.RecipeWriterPath]
 * machinery (deferred — see this module's README). When the deeper resolver
 * lands we'll add the weighted DFS variant here.
 */
class MetricFlowPathfinder {

    private val toolkit: Pathfinder<SemanticGraphNode, SemanticGraphEdge> = Pathfinder()

    /**
     * BFS-find all nodes reachable from [sourceNodes] respecting node / label
     * filters.
     */
    fun findDescendants(
        graph: SemanticGraph,
        sourceNodes: OrderedSet<SemanticGraphNode>,
        targetNodes: OrderedSet<SemanticGraphNode>,
        nodeAllowSet: Set<SemanticGraphNode>?,
        denyLabels: Set<MetricFlowGraphLabel>?,
        maxIterationCount: Int,
    ) = toolkit.findDescendants(
        graph = graph,
        sourceNodes = sourceNodes,
        targetNodes = targetNodes,
        nodeAllowSet = nodeAllowSet,
        denyLabels = denyLabels,
        maxIterationCount = maxIterationCount,
    )

    /** Convenience: BFS from [sourceNodes] using defaults. */
    fun findDescendants(
        graph: SemanticGraph,
        sourceNodes: OrderedSet<SemanticGraphNode>,
        targetNodes: OrderedSet<SemanticGraphNode>,
    ) = findDescendants(
        graph = graph,
        sourceNodes = sourceNodes,
        targetNodes = targetNodes,
        nodeAllowSet = null,
        denyLabels = null,
        maxIterationCount = PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS,
    )

    /** BFS-find all nodes that can reach [targetNodes]. */
    fun findAncestors(
        graph: SemanticGraph,
        sourceNodes: OrderedSet<SemanticGraphNode>,
        targetNodes: OrderedSet<SemanticGraphNode>,
        nodeAllowSet: Set<SemanticGraphNode>?,
        denyLabels: Set<MetricFlowGraphLabel>?,
        maxIterationCount: Int,
    ) = toolkit.findAncestors(
        graph = graph,
        sourceNodes = sourceNodes,
        targetNodes = targetNodes,
        nodeAllowSet = nodeAllowSet,
        denyLabels = denyLabels,
        maxIterationCount = maxIterationCount,
    )

    /**
     * Returns nodes reachable forward from the supplied source nodes (no target
     * filter).
     *
     * Implemented as a plain dedup-BFS over the graph's outgoing edges. We
     * deliberately don't reuse [Pathfinder.findDescendants] for this query
     * because that method treats `targetNodes` as terminal (short-circuits the
     * traversal once a candidate lands in the set). The "give me everything
     * reachable" query needs to keep expanding, which is exactly what this
     * loop does. Cycles are handled by the `seen` set — every node enters the
     * frontier at most once. Capped at
     * [PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS] hops to mirror the toolkit's
     * safety guard.
     */
    fun reachableFrom(
        graph: SemanticGraph,
        sourceNodes: Iterable<SemanticGraphNode>,
    ): OrderedSet<SemanticGraphNode> {
        val seen = cc.monomer.metricflow.common.util.collections.MutableOrderedSet<SemanticGraphNode>()
        seen.addAll(sourceNodes)
        var frontier: List<SemanticGraphNode> = sourceNodes.toList()
        var iterations = 0
        while (frontier.isNotEmpty() && iterations <= PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS) {
            val next = mutableListOf<SemanticGraphNode>()
            for (current in frontier) {
                for (edge in graph.edgesWithTailNode(current)) {
                    val head = edge.headNode
                    if (seen.add(head)) next.add(head)
                }
            }
            frontier = next
            iterations++
        }
        return seen
    }
}
