package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.graph.MetricFlowGraph
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.graph.MutableGraph
import cc.monomer.metricflow.common.util.collections.OrderedSet

/**
 * Read-only view of a semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/sg_interfaces.py::SemanticGraph`.
 *
 * The graph models the entity relationships configured in the semantic
 * manifest plus the recipe steps required to compute attributes by following
 * an edge.
 *
 * Edges flow in the direction "metric → attribute", i.e. a path from a metric
 * node to an attribute node describes the query needed to compute that
 * attribute for the metric.
 */
interface SemanticGraph : MetricFlowGraph<SemanticGraphNode, SemanticGraphEdge>

/**
 * Mutable implementation of [SemanticGraph] used during construction.
 *
 * Port of `MutableSemanticGraph`. Delegates to the toolkit [MutableGraph] but
 * stays typed to the sealed [SemanticGraphNode] / [SemanticGraphEdge] families.
 */
class MutableSemanticGraph private constructor(
    private val backing: MutableGraph<SemanticGraphNode, SemanticGraphEdge>,
) : SemanticGraph {

    override val nodes: OrderedSet<SemanticGraphNode> get() = backing.nodes
    override val edges: OrderedSet<SemanticGraphEdge> get() = backing.edges
    override val graphId get() = backing.graphId

    fun addNode(node: SemanticGraphNode) {
        backing.addNode(node)
    }

    fun addEdge(edge: SemanticGraphEdge) {
        backing.addEdge(edge)
    }

    fun addEdges(edges: Iterable<SemanticGraphEdge>) {
        backing.addEdges(edges)
    }

    override fun nodesWithLabels(vararg graphLabels: MetricFlowGraphLabel) =
        backing.nodesWithLabels(*graphLabels)

    override fun edgesWithTailNode(tailNode: cc.monomer.metricflow.common.graph.MetricFlowGraphNode) =
        backing.edgesWithTailNode(tailNode)

    override fun edgesWithHeadNode(headNode: cc.monomer.metricflow.common.graph.MetricFlowGraphNode) =
        backing.edgesWithHeadNode(headNode)

    override fun edgesWithLabel(label: MetricFlowGraphLabel) = backing.edgesWithLabel(label)

    override fun successors(node: cc.monomer.metricflow.common.graph.MetricFlowGraphNode) =
        backing.successors(node)

    override fun predecessors(node: cc.monomer.metricflow.common.graph.MetricFlowGraphNode) =
        backing.predecessors(node)

    companion object {
        /** Construct a fresh, empty mutable semantic graph. */
        fun create(): MutableSemanticGraph = MutableSemanticGraph(MutableGraph())
    }
}
