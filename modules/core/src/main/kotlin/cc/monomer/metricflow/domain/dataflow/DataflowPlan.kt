package cc.monomer.metricflow.domain.dataflow

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.MetricFlowDag
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Describes the flow of metric data as it goes from source nodes to sink nodes in the graph.
 *
 * Port of `metricflow.dataflow.dataflow_plan.DataflowPlan`.
 *
 * A dataflow plan has **exactly one sink node** ([renderNode]) — even though
 * [MetricFlowDag.sinkNodes] models a general list, metricflow always builds plans with a
 * single render target. The W9c plan converter walks the tree from this sink.
 *
 * Use [DataflowPlanNode.asPlan] to wrap an arbitrary node as a `DataflowPlan` when you need
 * plan-level properties such as [nodeCount] or [sourceSemanticModels] from a sub-DAG.
 */
class DataflowPlan(
    val renderNode: DataflowPlanNode,
    planId: DagId,
) : MetricFlowDag<DataflowPlanNode>(
    dagId = planId,
    sinkNodes = listOf(renderNode),
) {

    /** Construct a plan with an auto-generated DagId. */
    constructor(renderNode: DataflowPlanNode) : this(
        renderNode = renderNode,
        planId = DagId.fromIdPrefix(StaticIdPrefix.DATAFLOW_PLAN_PREFIX),
    )

    /** Convenience accessor mirroring Python `sink_node` (since we guarantee exactly one). */
    val sinkNode: DataflowPlanNode get() = renderNode

    /** Total number of nodes in this plan (counted once per unique node). */
    val nodeCount: Int
        get() = allNodesInSubgraph(sinkNode).size

    /**
     * Return the complete set of source semantic models for this plan.
     *
     * Port of Python `DataflowPlan.source_semantic_models`. Walks every node in the DAG and
     * collects each node's [DataflowPlanNode.inputSemanticModel] (non-null entries only).
     */
    val sourceSemanticModels: Set<SemanticModelReference>
        get() = buildSet {
            for (node in allNodesInSubgraph(sinkNode)) {
                node.inputSemanticModel?.let { add(it) }
            }
        }

    companion object {
        /**
         * Flatten the subgraph rooted at [node] into a list of every node reached upstream.
         *
         * Port of Python `DataflowPlan.__all_nodes_in_subgraph`. Note Python counts each reach
         * separately (so a shared parent is multi-counted); we use a [LinkedHashSet] here to
         * preserve traversal order while deduping — Python downstream callers only care about
         * the set-of-nodes view, so this is faithful to intent.
         */
        internal fun allNodesInSubgraph(node: DataflowPlanNode): List<DataflowPlanNode> {
            val seen = LinkedHashSet<DataflowPlanNode>()
            fun walk(current: DataflowPlanNode) {
                if (!seen.add(current)) return
                for (parent in current.parentNodes) walk(parent)
            }
            walk(node)
            return seen.toList()
        }
    }
}
