package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.metric_evaluation.plan.DerivedMetricsQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryDependencyEdge
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryPropertySet
import cc.monomer.metricflow.domain.metric_evaluation.plan.MutableMetricEvaluationPlan
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * Consolidate multiple [DerivedMetricsQueryNode]s with common sources into a
 * single node.
 *
 * Port of
 * `metricflow.metric_evaluation.passthrough.node_consolidator.DerivedMetricsNodeConsolidator`.
 *
 * Given two derived nodes A (computing `m0`) and B (computing `m1`) that share
 * the same source nodes `{C}`, the consolidator emits a new node D that
 * computes both `m0` and `m1`. This reduces the number of queries / joins in
 * the final plan. Aliased metrics are intentionally kept separate.
 */
class DerivedMetricsNodeConsolidator(
    nodesToConsolidate: OrderedSet<DerivedMetricsQueryNode>,
    correspondingSourceEdges: OrderedSet<MetricQueryDependencyEdge>,
) {

    private val nodesToConsolidate: FrozenOrderedSet<DerivedMetricsQueryNode> =
        FrozenOrderedSet(nodesToConsolidate)

    /** A sub-plan over just the candidate nodes — used for source-edge lookups. */
    private val subplan: MutableMetricEvaluationPlan = MutableMetricEvaluationPlan.create()

    init {
        subplan.addEdges(correspondingSourceEdges)
        validateConstructorInputs(correspondingSourceEdges)
    }

    private fun validateConstructorInputs(
        correspondingSourceEdges: OrderedSet<MetricQueryDependencyEdge>,
    ) {
        // Every edge's target node must be in `nodesToConsolidate`.
        val invalidTargets = correspondingSourceEdges.filter { it.targetNode !in nodesToConsolidate }
        if (invalidTargets.isNotEmpty()) {
            throw MetricFlowInternalError(
                "Source edges should reflect the dependencies of the given derived metrics nodes. " +
                    "invalidEdges=$invalidTargets nodesToConsolidate=$nodesToConsolidate",
            )
        }

        // Every edge's source node must NOT be in `nodesToConsolidate`.
        val invalidSources = correspondingSourceEdges.filter { it.sourceNode in nodesToConsolidate }
        if (invalidSources.isNotEmpty()) {
            throw MetricFlowInternalError(
                "Source edges should not have a source in the given set of derived metrics nodes. " +
                    "invalidEdges=$invalidSources derivedMetricNodes=$nodesToConsolidate",
            )
        }

        // Each pre-consolidation node should compute exactly one metric.
        for (node in nodesToConsolidate) {
            if (node.computedMetricSpecs.size != 1) {
                throw MetricFlowInternalError(
                    "Provided nodes should each compute exactly one derived metric. derivedMetricsNode=$node",
                )
            }
        }

        // Every node must have at least one source edge.
        val nodesWithoutSources = nodesToConsolidate
            .filter { subplan.sourceEdges(it).isEmpty() }
        if (nodesWithoutSources.isNotEmpty()) {
            throw MetricFlowInternalError(
                "Every node to consolidate should have at least one corresponding source edge. " +
                    "nodesWithoutSources=$nodesWithoutSources " +
                    "correspondingSourceEdges=${correspondingSourceEdges.toList()}",
            )
        }
    }

    /** Return the consolidated nodes and the new edges. */
    fun consolidateNodes():
        Pair<OrderedSet<DerivedMetricsQueryNode>, OrderedSet<MetricQueryDependencyEdge>> {
        val newNodes: MutableOrderedSet<DerivedMetricsQueryNode> = MutableOrderedSet()
        val newEdges: MutableOrderedSet<MetricQueryDependencyEdge> = MutableOrderedSet()

        val grouped = LinkedHashMap<NodeConsolidationKey, MutableList<DerivedMetricsQueryNode>>()
        for (node in nodesToConsolidate) {
            grouped.getOrPut(nodeConsolidationKey(node)) { mutableListOf() }.add(node)
        }

        for ((consolidationKey, groupedNodes) in grouped) {
            // Aliased metrics stay as separate queries.
            if (consolidationKey.aliasedMetricSpec != null) {
                newNodes.addAll(groupedNodes)
                for (node in groupedNodes) newEdges.addAll(subplan.sourceEdges(node))
                continue
            }

            val (consolidatedNode, edges) = consolidateNodesForNonAliasedMetrics(
                nodes = groupedNodes,
                queryProperties = consolidationKey.queryProperties,
            )
            newNodes.add(consolidatedNode)
            newEdges.addAll(edges)
        }

        return newNodes to newEdges
    }

    private fun nodeConsolidationKey(node: DerivedMetricsQueryNode): NodeConsolidationKey {
        val computedMetricSpec = node.computedMetricSpecs.first()
        return NodeConsolidationKey(
            aliasedMetricSpec = computedMetricSpec.takeIf { it.alias != null },
            sourceNodes = FrozenOrderedSet(subplan.sourceNodes(node).toList()),
            queryProperties = node.queryProperties,
        )
    }

    private fun consolidateNodesForNonAliasedMetrics(
        nodes: List<DerivedMetricsQueryNode>,
        queryProperties: MetricQueryPropertySet,
    ): Pair<DerivedMetricsQueryNode, OrderedSet<MetricQueryDependencyEdge>> {
        if (nodes.isEmpty()) throw MetricFlowInternalError("No nodes passed in for consolidation.")
        if (nodes.size == 1) {
            val node = nodes[0]
            val edges = subplan.sourceEdges(node)
            return node to edges
        }

        // With shared sources, passthrough metric specs should match. Use intersection defensively.
        val passthroughIntersection: MutableOrderedSet<MetricSpec> = MutableOrderedSet(nodes[0].passthroughMetricSpecs)
        for (n in nodes.drop(1)) {
            passthroughIntersection.retainAll(n.passthroughMetricSpecs.toSet())
        }

        val computedMetricSpecs = nodes.flatMap { it.computedMetricSpecs }

        val consolidatedNode = DerivedMetricsQueryNode.create(
            computedMetricSpecs = computedMetricSpecs,
            passthroughMetricSpecs = passthroughIntersection.toList(),
            queryProperties = queryProperties,
        )

        // Build new edges. Multiple input nodes can map to the same edge on the consolidated node,
        // so use an ordered set to dedup.
        val edgesForConsolidated: MutableOrderedSet<MetricQueryDependencyEdge> = MutableOrderedSet()
        for (node in nodes) {
            for (sourceEdge in subplan.sourceEdges(node)) {
                edgesForConsolidated.add(
                    MetricQueryDependencyEdge.create(
                        targetNode = consolidatedNode,
                        targetNodeOutputSpec = sourceEdge.targetNodeOutputSpec,
                        sourceNode = sourceEdge.sourceNode,
                        sourceNodeOutputSpec = sourceEdge.sourceNodeOutputSpec,
                    ),
                )
            }
        }
        return consolidatedNode to edgesForConsolidated
    }
}

/**
 * Grouping key used when deciding which [DerivedMetricsQueryNode]s can be
 * consolidated into a single node.
 *
 * Port of `NodeConsolidationKey`.
 *
 * Two nodes are grouped iff they have the same source nodes and query
 * properties. Aliased metrics keep their own group (each aliased metric maps
 * to its own node).
 */
data class NodeConsolidationKey(
    /** Set on aliased metrics; ensures each aliased metric maps to its own node. */
    val aliasedMetricSpec: MetricSpec?,
    val sourceNodes: OrderedSet<MetricQueryNode>,
    val queryProperties: MetricQueryPropertySet,
)
