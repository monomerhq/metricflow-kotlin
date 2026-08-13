package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor

/**
 * Adds an internally generated UUID column to the input dataset.
 *
 * Port of `metricflow.dataflow.nodes.add_generated_uuid.AddGeneratedUuidColumnNode`. Carries no
 * state — two nodes of this type are [functionallyIdentical] iff they are the same class.
 */
class AddGeneratedUuidColumnNode(parentNode: DataflowPlanNode) :
    DataflowPlanNode(parentNodes = listOf(parentNode)) {

    /** The (single) parent of this node. */
    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Adds an internally generated UUID column"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_ADD_UUID_COLUMN_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitAddGeneratedUuidColumnNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is AddGeneratedUuidColumnNode

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): AddGeneratedUuidColumnNode {
        check(newParentNodes.size == 1) {
            "AddGeneratedUuidColumnNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return AddGeneratedUuidColumnNode(newParentNodes[0])
    }
}
