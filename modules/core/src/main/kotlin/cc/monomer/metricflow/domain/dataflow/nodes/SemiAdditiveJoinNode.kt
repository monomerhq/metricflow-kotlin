package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Filters rows by aggregating a non-additive time dimension.
 *
 * Port of `metricflow.dataflow.nodes.semi_additive_join.SemiAdditiveJoinNode`.
 *
 * The classic example: an "account balances" table with rows per `(date, user, balance)`. To
 * get the latest balance per user, MIN/MAX the date by user, then join back to recover the
 * balance corresponding to that date.
 *
 * When [queriedTimeDimensionSpec] is set, the filter operates inside windows of that
 * granularity instead of over the whole table.
 *
 * @property entityReferences Entities to group the aggregation by (empty = no grouping).
 * @property timeDimensionSpec The time dimension that the filtering aggregation runs over.
 * @property aggByFunction Aggregation function applied to [timeDimensionSpec] (MAX or MIN).
 * @property queriedTimeDimensionSpec When set, defines the windowing granularity.
 */
class SemiAdditiveJoinNode(
    parentNode: DataflowPlanNode,
    val entityReferences: List<EntityReference>,
    val timeDimensionSpec: TimeDimensionSpec,
    val aggByFunction: AggregationType,
    val queriedTimeDimensionSpec: TimeDimensionSpec?,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String
        get() {
            val groupBy = queriedTimeDimensionSpec?.elementName
            return "Join on ${aggByFunction.name}(${timeDimensionSpec.elementName}) and " +
                "${entityReferences.map { it.elementName }} grouping by $groupBy"
        }

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_SEMI_ADDITIVE_JOIN_ID_PREFIX

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitSemiAdditiveJoinNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is SemiAdditiveJoinNode &&
            other.entityReferences == entityReferences &&
            other.timeDimensionSpec == timeDimensionSpec &&
            other.aggByFunction == aggByFunction &&
            other.queriedTimeDimensionSpec == queriedTimeDimensionSpec

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): SemiAdditiveJoinNode {
        check(newParentNodes.size == 1) {
            "SemiAdditiveJoinNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return SemiAdditiveJoinNode(
            parentNode = newParentNodes[0],
            entityReferences = entityReferences,
            timeDimensionSpec = timeDimensionSpec,
            aggByFunction = aggByFunction,
            queriedTimeDimensionSpec = queriedTimeDimensionSpec,
        )
    }
}
