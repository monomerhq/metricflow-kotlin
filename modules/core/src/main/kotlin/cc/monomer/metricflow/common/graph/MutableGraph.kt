package cc.monomer.metricflow.common.graph

import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet

/**
 * Mutable [MetricFlowGraph] implementation.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.mutable_graph.MutableGraph`.
 * Adding nodes or edges invalidates the [graphId] (a fresh [SequentialGraphId]
 * is generated) so cached views downstream can detect change.
 *
 * Inserting an [E] whose endpoints aren't yet in the graph automatically
 * adds them.
 */
class MutableGraph<N : MetricFlowGraphNode, E : MetricFlowGraphEdge<N>> : MetricFlowGraph<N, E> {

    private val _nodes: MutableOrderedSet<N> = MutableOrderedSet()
    private val _edges: MutableOrderedSet<E> = MutableOrderedSet()
    private var _graphId: MetricFlowGraphId = SequentialGraphId.create()

    private val labelToNodes: HashMap<MetricFlowGraphLabel, MutableOrderedSet<N>> = HashMap()
    private val labelToEdges: HashMap<MetricFlowGraphLabel, MutableOrderedSet<E>> = HashMap()
    private val tailNodeToEdges: HashMap<MetricFlowGraphNode, MutableOrderedSet<E>> = HashMap()
    private val headNodeToEdges: HashMap<MetricFlowGraphNode, MutableOrderedSet<E>> = HashMap()
    private val nodeToSuccessorNodes: HashMap<MetricFlowGraphNode, MutableOrderedSet<N>> = HashMap()
    private val nodeToPredecessorNodes: HashMap<MetricFlowGraphNode, MutableOrderedSet<N>> = HashMap()

    override val nodes: OrderedSet<N> get() = _nodes
    override val edges: OrderedSet<E> get() = _edges
    override val graphId: MetricFlowGraphId get() = _graphId

    fun addNode(node: N) {
        addNodes(listOf(node))
    }

    fun addNodes(toAdd: Iterable<N>) {
        for (node in toAdd) {
            if (_nodes.add(node)) {
                for (label in node.labels) {
                    labelToNodes.getOrPut(label) { MutableOrderedSet() }.add(node)
                }
            }
        }
        _graphId = SequentialGraphId.create()
    }

    fun addEdge(edge: E) {
        addEdges(listOf(edge))
    }

    fun addEdges(toAdd: Iterable<E>) {
        val list = toAdd.toList()
        // First add endpoints not yet in the graph.
        val endpoints = MutableOrderedSet<N>()
        for (e in list) {
            endpoints.add(e.tailNode)
            endpoints.add(e.headNode)
        }
        endpoints.removeAll(_nodes)
        if (endpoints.isNotEmpty()) addNodes(endpoints)

        for (e in list) {
            tailNodeToEdges.getOrPut(e.tailNode) { MutableOrderedSet() }.add(e)
            headNodeToEdges.getOrPut(e.headNode) { MutableOrderedSet() }.add(e)
            nodeToSuccessorNodes.getOrPut(e.tailNode) { MutableOrderedSet() }.add(e.headNode)
            nodeToPredecessorNodes.getOrPut(e.headNode) { MutableOrderedSet() }.add(e.tailNode)
            for (label in e.labels) {
                labelToEdges.getOrPut(label) { MutableOrderedSet() }.add(e)
            }
            _edges.add(e)
        }
        _graphId = SequentialGraphId.create()
    }

    override fun nodesWithLabels(vararg graphLabels: MetricFlowGraphLabel): OrderedSet<N> {
        val result = MutableOrderedSet<N>()
        for (label in graphLabels) {
            val matches = labelToNodes[label] ?: continue
            result.addAll(matches)
        }
        return result
    }

    override fun edgesWithTailNode(tailNode: MetricFlowGraphNode): OrderedSet<E> =
        tailNodeToEdges[tailNode] ?: MutableOrderedSet()

    override fun edgesWithHeadNode(headNode: MetricFlowGraphNode): OrderedSet<E> =
        headNodeToEdges[headNode] ?: MutableOrderedSet()

    override fun edgesWithLabel(label: MetricFlowGraphLabel): OrderedSet<E> =
        labelToEdges[label] ?: MutableOrderedSet()

    override fun successors(node: MetricFlowGraphNode): OrderedSet<N> =
        nodeToSuccessorNodes[node] ?: MutableOrderedSet()

    override fun predecessors(node: MetricFlowGraphNode): OrderedSet<N> =
        nodeToPredecessorNodes[node] ?: MutableOrderedSet()
}
