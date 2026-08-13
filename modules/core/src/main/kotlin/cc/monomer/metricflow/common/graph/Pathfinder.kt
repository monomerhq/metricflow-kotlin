package cc.monomer.metricflow.common.graph

import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet

/** Default upper bound on BFS iterations. */
const val PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS: Int = 100

/** Result returned from [Pathfinder.findDescendants]. */
data class FindDescendantsResult<N : MetricFlowGraphNode>(
    val reachableNodes: OrderedSet<N>,
    val reachableTargetNodes: OrderedSet<N>,
    val labelsCollectedDuringTraversal: OrderedSet<MetricFlowGraphLabel>,
    val finishIterationIndex: Int,
    val targetNodeToReachableSourceNodes: Map<N, OrderedSet<N>>,
)

/** Result returned from [Pathfinder.findAncestors]. */
data class FindAncestorsResult<N : MetricFlowGraphNode>(
    val reachableNodes: OrderedSet<N>,
    val reachableSourceNodes: OrderedSet<N>,
    val sourceNodeToReachableTargetNodes: Map<N, OrderedSet<N>>,
    val labelsCollectedDuringTraversal: OrderedSet<MetricFlowGraphLabel>,
    val finishIterationIndex: Int,
)

/**
 * Finds paths and related node-sets via BFS.
 *
 * Trimmed port of `metricflow_semantics.toolkit.mf_graph.path_finding.pathfinder.MetricFlowPathfinder` —
 * we keep [findDescendants] / [findAncestors] (the two methods actually
 * called from the engine's reachable code) and drop the DFS path enumeration
 * machinery for later waves where it's needed.
 */
class Pathfinder<N : MetricFlowGraphNode, E : MetricFlowGraphEdge<N>> {

    /** BFS forward through [graph] starting from [sourceNodes]. */
    fun findDescendants(
        graph: MetricFlowGraph<N, E>,
        sourceNodes: OrderedSet<N>,
        targetNodes: OrderedSet<N>,
        nodeAllowSet: Set<N>?,
        denyLabels: Set<MetricFlowGraphLabel>?,
        maxIterationCount: Int,
    ): FindDescendantsResult<N> {
        var batch: List<N> = sourceNodes.toList()
        val reachedTargets = MutableOrderedSet<N>()
        val reachable = MutableOrderedSet<N>().apply { addAll(sourceNodes) }
        val labels = MutableOrderedSet<MetricFlowGraphLabel>()
        for (n in sourceNodes) labels.addAll(n.labels)

        val nodeToReachableSources = HashMap<N, MutableOrderedSet<N>>()
        for (n in sourceNodes) nodeToReachableSources.getOrPut(n) { MutableOrderedSet() }.add(n)

        var iterationIndex = 0
        while (iterationIndex <= maxIterationCount) {
            if (batch.isEmpty()) break
            val nextBatch = mutableListOf<N>()
            for (current in batch) {
                if (current in targetNodes) {
                    reachedTargets.add(current)
                    continue
                }
                val outgoing = graph.edgesWithTailNode(current)
                for (edge in outgoing) {
                    val traversalLabels = edge.labelsForPathAddition
                    if (denyLabels != null && traversalLabels.any { it in denyLabels }) continue
                    val head = edge.headNode
                    if (nodeAllowSet != null && head !in nodeAllowSet) continue
                    nodeToReachableSources.getOrPut(head) { MutableOrderedSet() }
                        .addAll(nodeToReachableSources[current] ?: emptySet())
                    reachable.add(head)
                    nextBatch.add(head)
                    labels.addAll(traversalLabels)
                }
            }
            batch = nextBatch
            iterationIndex++
        }

        return FindDescendantsResult(
            reachableNodes = reachable,
            reachableTargetNodes = reachedTargets,
            labelsCollectedDuringTraversal = labels,
            finishIterationIndex = iterationIndex,
            targetNodeToReachableSourceNodes = reachedTargets.associateWith { node ->
                nodeToReachableSources[node] ?: MutableOrderedSet()
            },
        )
    }

    /** BFS backwards through [graph] starting from [targetNodes]. */
    fun findAncestors(
        graph: MetricFlowGraph<N, E>,
        sourceNodes: OrderedSet<N>,
        targetNodes: OrderedSet<N>,
        nodeAllowSet: Set<N>?,
        denyLabels: Set<MetricFlowGraphLabel>?,
        maxIterationCount: Int,
    ): FindAncestorsResult<N> {
        var batch: List<N> = targetNodes.toList()
        val labels = MutableOrderedSet<MetricFlowGraphLabel>()
        for (n in targetNodes) labels.addAll(n.labels)
        val reachable = MutableOrderedSet<N>().apply { addAll(sourceNodes); addAll(targetNodes) }
        val reachedSources = MutableOrderedSet<N>()
        val nodeToReachableTargets = HashMap<N, MutableOrderedSet<N>>()
        for (n in targetNodes) nodeToReachableTargets.getOrPut(n) { MutableOrderedSet() }.add(n)

        var iterationIndex = 0
        while (iterationIndex <= maxIterationCount) {
            if (batch.isEmpty()) break
            val nextBatch = mutableListOf<N>()
            for (current in batch) {
                if (current in sourceNodes) {
                    reachedSources.add(current)
                    continue
                }
                for (edge in graph.edgesWithHeadNode(current)) {
                    val predecessor = edge.tailNode
                    if (nodeAllowSet != null && predecessor !in nodeAllowSet) continue
                    if (denyLabels != null && predecessor.labels.any { it in denyLabels }) continue
                    nextBatch.add(predecessor)
                    labels.addAll(edge.labels)
                    labels.addAll(predecessor.labels)
                    reachable.add(predecessor)
                    nodeToReachableTargets.getOrPut(predecessor) { MutableOrderedSet() }
                        .addAll(nodeToReachableTargets[edge.headNode] ?: emptySet())
                }
            }
            batch = nextBatch
            iterationIndex++
        }

        return FindAncestorsResult(
            reachableNodes = reachable,
            reachableSourceNodes = reachedSources,
            sourceNodeToReachableTargetNodes = reachedSources.associateWith { node ->
                nodeToReachableTargets[node] ?: MutableOrderedSet()
            },
            labelsCollectedDuringTraversal = labels,
            finishIterationIndex = iterationIndex,
        )
    }
}
