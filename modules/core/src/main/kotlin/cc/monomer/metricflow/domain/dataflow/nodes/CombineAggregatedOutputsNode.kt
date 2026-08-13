package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor

/**
 * Combines metrics from different parent nodes into a single output dataset (UNION-style).
 *
 * Port of `metricflow.dataflow.nodes.combine_aggregated_outputs.CombineAggregatedOutputsNode`.
 *
 * Requires at least **two** parent nodes — combining a single dataset would be a no-op and is
 * disallowed by the constructor.
 */
class CombineAggregatedOutputsNode(parentNodes: List<DataflowPlanNode>) :
    DataflowPlanNode(parentNodes = parentNodes) {

    init {
        check(parentNodes.size > 1) {
            "The CombineAggregatedOutputsNode is intended to merge the output datasets from 2 or more nodes, " +
                "but this node is being initialized with only ${parentNodes.size} parent(s)."
        }
    }

    override val description: String get() = "Combine Aggregated Outputs"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_COMBINE_AGGREGATED_OUTPUTS_ID_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitCombineAggregatedOutputsNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is CombineAggregatedOutputsNode

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): CombineAggregatedOutputsNode =
        CombineAggregatedOutputsNode(parentNodes = newParentNodes)
}
