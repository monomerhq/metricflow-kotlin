package cc.monomer.metricflow.domain.query.group_by.resolution_dag

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.MetricFlowDag
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.GroupByItemResolutionNode

/**
 * The group-by-item resolution DAG.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.dag.GroupByItemResolutionDag`.
 *
 * Owns exactly one [sinkNode] — the query node. Candidates flow upward
 * from the source leaves (one per simple metric) through any complex-metric
 * resolution nodes and are intersected at the sink. The Python class is a
 * specialised `MetricFlowDag`; the Kotlin port preserves the inheritance
 * relationship.
 */
class GroupByItemResolutionDag(
    val sinkNode: GroupByItemResolutionNode,
) : MetricFlowDag<GroupByItemResolutionNode>(
    dagId = DagId.fromIdPrefix(StaticIdPrefix.GROUP_BY_ITEM_RESOLUTION_DAG),
    sinkNodes = listOf(sinkNode),
)
