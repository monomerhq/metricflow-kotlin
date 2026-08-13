package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.graph.MetricFlowGraph
import cc.monomer.metricflow.common.graph.MetricFlowGraphId
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.graph.MutableGraph
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet

/**
 * A directed acyclic graph that describes how a set of metrics should be
 * evaluated.
 *
 * Port of `metricflow.metric_evaluation.plan.me_plan.MetricEvaluationPlan`.
 *
 * Nodes are queries that compute or pass through metrics; edges point from
 * the target node (which depends on a metric) to a source node (which provides
 * it). Because metrics can be defined in terms of other metrics, this is the
 * natural model — a "derived metric depends on its inputs" graph.
 *
 * Using the SQL analogy, the [TopLevelQueryNode] represents the top-level
 * SELECT and the dependency nodes are subqueries.
 *
 * Invariants enforced by [validate]:
 *
 * 1. Exactly one [TopLevelQueryNode] is present.
 * 2. Every edge's `targetNodeOutputSpec` is in the target node's outputs.
 * 3. Every edge's `sourceNodeOutputSpec` is in the source node's outputs.
 * 4. Every passthrough metric appears on at least one source edge.
 */
interface MetricEvaluationPlan : MetricFlowGraph<MetricQueryNode, MetricQueryDependencyEdge> {

    /** All edges to the source nodes of [targetNode]. */
    fun sourceEdges(targetNode: MetricQueryNode): OrderedSet<MetricQueryDependencyEdge> =
        edgesWithTailNode(targetNode)

    /** All edges from the nodes that have [sourceNode] as a source. */
    fun targetEdges(sourceNode: MetricQueryNode): OrderedSet<MetricQueryDependencyEdge> =
        edgesWithHeadNode(sourceNode)

    /** Source (dependency) nodes for [node]. */
    fun sourceNodes(node: MetricQueryNode): OrderedSet<MetricQueryNode> = successors(node)

    /** Target nodes — nodes that have [node] as one of their sources. */
    fun targetNodes(node: MetricQueryNode): OrderedSet<MetricQueryNode> = predecessors(node)

    /**
     * Return nodes in depth-first traversal order — i.e. each node appears
     * after all its sources.
     *
     * Helps readability when printing the plan: a derived metric appears
     * after the simple metrics it depends on.
     */
    fun nodesInDfsOrder(): OrderedSet<MetricQueryNode> {
        val result = MutableOrderedSet<MetricQueryNode>()
        val rootNodes = nodes.filter { targetNodes(it).isEmpty() }
        if (rootNodes.isEmpty()) return FrozenOrderedSet()

        val visited = HashSet<MetricQueryNode>()
        val stack = ArrayDeque<MetricQueryNode>()
        for (root in rootNodes.reversed()) stack.addLast(root)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (!visited.add(current)) continue
            // Emit children first by pushing in reverse so they're visited in declaration order.
            val children = sourceNodes(current).toList()
            for (child in children.reversed()) {
                if (child !in visited) stack.addLast(child)
            }
            result.add(current)
        }

        return result
    }

    /**
     * Validate that all nodes and edges satisfy metric-evaluation invariants.
     * Port of `MetricEvaluationPlan.validate`.
     */
    fun validate() {
        val topLevelQueryNodes = nodesWithLabels(TopLevelQueryLabel)
        if (topLevelQueryNodes.size != 1) {
            throw MetricFlowInternalError(
                "The metric evaluation plan does not have exactly 1 top-level " +
                    "query node. This is a bug in the planner. " +
                    "topLevelQueryNodes=${topLevelQueryNodes.toList()}",
            )
        }

        val passthroughValidator = PassthroughMetricSpecValidator(this)
        for (node in nodes) {
            val outputMetricSpecs = node.outputMetricSpecs
            for (targetEdge in targetEdges(node)) {
                if (targetEdge.sourceNodeOutputSpec !in outputMetricSpecs) {
                    throw MetricFlowInternalError(
                        "An edge from the target node to a source node states " +
                            "that the source node outputs a spec that is not described " +
                            "by the output specs of the source node. This indicates incorrect " +
                            "graph construction. sourceNode=$node targetEdge=$targetEdge",
                    )
                }
            }

            for (sourceEdge in sourceEdges(node)) {
                if (sourceEdge.targetNodeOutputSpec !in outputMetricSpecs) {
                    throw MetricFlowInternalError(
                        "An edge from the target node to a source node states " +
                            "that the target node outputs a spec that is not described " +
                            "by the output specs of the target node. This indicates incorrect " +
                            "graph construction. targetNode=$node sourceEdge=$sourceEdge",
                    )
                }
            }

            node.accept(passthroughValidator)
        }
    }

    companion object {
        /** Cap on recursion depth in metric definitions. Matches Python's `MAX_METRIC_DEFINITION_RECURSION_DEPTH`. */
        const val MAX_METRIC_DEFINITION_RECURSION_DEPTH: Int = 100
    }
}

/**
 * Visitor that checks the passthrough invariant for derived / top-level nodes.
 *
 * Port of `MetricEvaluationPlan._PassthroughMetricSpecValidator`.
 */
private class PassthroughMetricSpecValidator(
    private val plan: MetricEvaluationPlan,
) : MetricQueryNodeVisitor<Unit> {

    override fun visitSimpleMetricsQueryNode(node: SimpleMetricsQueryNode) {
        // Simple metric queries don't pass through metrics.
    }

    override fun visitCumulativeMetricQueryNode(node: CumulativeMetricQueryNode) {
        // Cumulative metric queries don't pass through metrics.
    }

    override fun visitConversionMetricQueryNode(node: ConversionMetricQueryNode) {
        // Conversion metric queries don't pass through metrics.
    }

    override fun visitDerivedMetricsQueryNode(node: DerivedMetricsQueryNode) {
        checkPassthroughMetricSpecs(node, node.passthroughMetricSpecs)
    }

    override fun visitTopLevelQueryNode(node: TopLevelQueryNode) {
        if (node.passthroughMetricSpecs.isEmpty()) {
            throw MetricFlowInternalError("A top-level query node must pass through at least one metric: $node")
        }
        checkPassthroughMetricSpecs(node, node.passthroughMetricSpecs)
    }

    private fun checkPassthroughMetricSpecs(
        node: MetricQueryNode,
        passthroughMetricSpecs: List<cc.monomer.metricflow.domain.spec.MetricSpec>,
    ) {
        if (passthroughMetricSpecs.isEmpty()) return
        val sourceEdges = plan.edgesWithTailNode(node)
        val sourceOutputSpecs = sourceEdges.map { it.sourceNodeOutputSpec }.toSet()

        for (passthroughSpec in passthroughMetricSpecs) {
            if (passthroughSpec !in sourceOutputSpecs) {
                throw MetricFlowInternalError(
                    "A passthrough metric is not present in an edge to a source node. " +
                        "This indicates incorrect plan construction. " +
                        "passthroughSpec=$passthroughSpec sourceEdges=${sourceEdges.toList()}",
                )
            }
        }
    }
}

/**
 * Mutable [MetricEvaluationPlan] used during planner construction.
 *
 * Port of `metricflow.metric_evaluation.plan.me_plan.MutableMetricEvaluationPlan`.
 *
 * Internally delegates to a [MutableGraph] for storage. The Kotlin port uses
 * composition rather than multiple inheritance because Kotlin classes can't
 * extend two concrete classes the way Python can.
 */
class MutableMetricEvaluationPlan private constructor(
    private val backing: MutableGraph<MetricQueryNode, MetricQueryDependencyEdge>,
) : MetricEvaluationPlan {

    override val nodes: OrderedSet<MetricQueryNode> get() = backing.nodes
    override val edges: OrderedSet<MetricQueryDependencyEdge> get() = backing.edges
    override val graphId: MetricFlowGraphId get() = backing.graphId

    override fun nodesWithLabels(vararg graphLabels: MetricFlowGraphLabel): OrderedSet<MetricQueryNode> =
        backing.nodesWithLabels(*graphLabels)

    override fun edgesWithTailNode(tailNode: cc.monomer.metricflow.common.graph.MetricFlowGraphNode): OrderedSet<MetricQueryDependencyEdge> =
        backing.edgesWithTailNode(tailNode)

    override fun edgesWithHeadNode(headNode: cc.monomer.metricflow.common.graph.MetricFlowGraphNode): OrderedSet<MetricQueryDependencyEdge> =
        backing.edgesWithHeadNode(headNode)

    override fun edgesWithLabel(label: MetricFlowGraphLabel): OrderedSet<MetricQueryDependencyEdge> =
        backing.edgesWithLabel(label)

    override fun successors(node: cc.monomer.metricflow.common.graph.MetricFlowGraphNode): OrderedSet<MetricQueryNode> =
        backing.successors(node)

    override fun predecessors(node: cc.monomer.metricflow.common.graph.MetricFlowGraphNode): OrderedSet<MetricQueryNode> =
        backing.predecessors(node)

    /** Add a single node to the plan. */
    fun addNode(node: MetricQueryNode) {
        backing.addNode(node)
    }

    /** Add multiple nodes. */
    fun addNodes(toAdd: Iterable<MetricQueryNode>) {
        backing.addNodes(toAdd)
    }

    /** Add a single edge to the plan. */
    fun addEdge(edge: MetricQueryDependencyEdge) {
        backing.addEdge(edge)
    }

    /** Add multiple edges. */
    fun addEdges(toAdd: Iterable<MetricQueryDependencyEdge>) {
        backing.addEdges(toAdd)
    }

    companion object {
        /** Create an empty mutable plan. */
        fun create(): MutableMetricEvaluationPlan = MutableMetricEvaluationPlan(MutableGraph())
    }
}
