package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.dataflow.support.PartitionDimensionJoinDescription
import cc.monomer.metricflow.domain.dataflow.support.PartitionTimeDimensionJoinDescription
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType

/**
 * Encapsulates join constraints around validity windows.
 *
 * Port of `metricflow.dataflow.nodes.join_to_base.ValidityWindowJoinDescription`.
 */
data class ValidityWindowJoinDescription(
    val windowStartDimension: TimeDimensionSpec,
    val windowEndDimension: TimeDimensionSpec,
)

/**
 * Describes how data from a node should be joined to data from another node.
 *
 * Port of `metricflow.dataflow.nodes.join_to_base.JoinDescription`. The Python class enforces
 * a single invariant in `__post_init__`: `join_on_entity` is required unless the join is a
 * `CROSS JOIN`. We reproduce the check here.
 */
data class JoinDescription(
    val joinNode: DataflowPlanNode,
    val joinOnEntity: EntityReference?,
    val joinType: SqlJoinType,
    val joinOnPartitionDimensions: List<PartitionDimensionJoinDescription>,
    val joinOnPartitionTimeDimensions: List<PartitionTimeDimensionJoinDescription>,
    val validityWindow: ValidityWindowJoinDescription?,
) {
    init {
        if (joinOnEntity == null && joinType != SqlJoinType.CROSS_JOIN) {
            error("`join_on_entity` is required unless using CROSS JOIN.")
        }
    }
}

/**
 * Joins data from other nodes via the entities in the inputs.
 *
 * Port of `metricflow.dataflow.nodes.join_to_base.JoinOnEntitiesNode`. The visitor dispatch is
 * `visit_join_on_entities_node`.
 *
 * @property leftNode Node with the standard output (the left side of every join).
 * @property joinTargets Other sources joined to [leftNode] in order.
 */
class JoinOnEntitiesNode(
    val leftNode: DataflowPlanNode,
    val joinTargets: List<JoinDescription>,
) : DataflowPlanNode(parentNodes = buildList {
    add(leftNode)
    for (target in joinTargets) add(target.joinNode)
}) {

    override val description: String get() = "Join Standard Outputs"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_JOIN_TO_STANDARD_OUTPUT_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + joinTargets.mapIndexed { i, target ->
            DisplayedProperty("join${i}_for_node_id_${target.joinNode.nodeId}", target)
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitJoinOnEntitiesNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean {
        if (other !is JoinOnEntitiesNode) return false
        if (joinTargets.size != other.joinTargets.size) return false
        for (i in joinTargets.indices) {
            val a = joinTargets[i]
            val b = other.joinTargets[i]
            if (a.joinOnEntity != b.joinOnEntity ||
                a.joinOnPartitionDimensions != b.joinOnPartitionDimensions ||
                a.joinOnPartitionTimeDimensions != b.joinOnPartitionTimeDimensions ||
                a.validityWindow != b.validityWindow
            ) return false
        }
        return true
    }

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): JoinOnEntitiesNode {
        check(newParentNodes.size > 1) {
            "JoinOnEntitiesNode expects a left node plus at least one join target."
        }
        val newLeft = newParentNodes[0]
        val newJoinNodes = newParentNodes.drop(1)
        check(newJoinNodes.size == joinTargets.size) {
            "JoinOnEntitiesNode expects ${joinTargets.size} join targets; got ${newJoinNodes.size}."
        }
        return JoinOnEntitiesNode(
            leftNode = newLeft,
            joinTargets = joinTargets.mapIndexed { i, old ->
                old.copy(joinNode = newJoinNodes[i])
            },
        )
    }
}
