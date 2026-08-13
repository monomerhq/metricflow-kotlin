package cc.monomer.metricflow.common.graph

import cc.monomer.metricflow.common.logging.MetricFlowPrettyFormattable
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet

/**
 * Base class for nodes in a directed graph.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.mf_graph.MetricFlowGraphNode`.
 * Concrete subclasses provide a [nodeDescriptor] and (optionally) a set of
 * [labels] for label-based lookups.
 */
abstract class MetricFlowGraphNode : MetricFlowGraphElement, MetricFlowPrettyFormattable {

    abstract val nodeDescriptor: MetricFlowGraphNodeDescriptor

    /** Labels for label-based lookup. Empty by default. */
    open val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet()

    override fun prettyFormat(): String =
        "${this::class.simpleName}(node=${nodeDescriptor.nodeName})"
}

/**
 * Base class for edges in a directed graph.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.mf_graph.MetricFlowGraphEdge`.
 * An edge is visualised as an arrow `tailNode -> headNode`.
 */
abstract class MetricFlowGraphEdge<N : MetricFlowGraphNode>(
    val tailNode: N,
    val headNode: N,
) : MetricFlowGraphElement, MetricFlowPrettyFormattable {

    val nodePair: Pair<N, N> get() = tailNode to headNode

    open val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet()

    /** Returns the inverse edge (tail and head swapped). Subclasses choose their concrete type. */
    abstract fun inverse(): MetricFlowGraphEdge<N>

    /** Labels for collecting during traversal — edge labels plus labels of the head node. */
    val labelsForPathAddition: OrderedSet<MetricFlowGraphLabel>
        get() = labels.union(headNode.labels)

    override fun prettyFormat(): String =
        "${this::class.simpleName}(${tailNode.nodeDescriptor.nodeName} -> ${headNode.nodeDescriptor.nodeName})"
}

/**
 * Base interface for a directed graph.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.mf_graph.MetricFlowGraph`.
 * The Python class is large; we keep the read-only surface (nodes, edges,
 * neighbours, label lookups). The mutable implementation is [MutableGraph].
 */
interface MetricFlowGraph<N : MetricFlowGraphNode, E : MetricFlowGraphEdge<N>> : MetricFlowPrettyFormattable {

    val nodes: OrderedSet<N>
    val edges: OrderedSet<E>
    val graphId: MetricFlowGraphId

    fun nodesWithLabels(vararg graphLabels: MetricFlowGraphLabel): OrderedSet<N>

    /** Returns the single node matching [label]. Throws if not exactly one matches. */
    fun nodeWithLabel(label: MetricFlowGraphLabel): N {
        val matches = nodesWithLabels(label)
        if (matches.size != 1) {
            throw NoSuchElementException(
                "Did not find exactly one node with label $label (found ${matches.size})",
            )
        }
        return matches.first()
    }

    fun edgesWithTailNode(tailNode: MetricFlowGraphNode): OrderedSet<E>
    fun edgesWithHeadNode(headNode: MetricFlowGraphNode): OrderedSet<E>
    fun edgesWithLabel(label: MetricFlowGraphLabel): OrderedSet<E>

    fun successors(node: MetricFlowGraphNode): OrderedSet<N>
    fun predecessors(node: MetricFlowGraphNode): OrderedSet<N>

    override fun prettyFormat(): String =
        "${this::class.simpleName}(node_count=${nodes.size}, edge_count=${edges.size})"
}
