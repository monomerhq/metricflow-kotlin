package cc.monomer.metricflow.domain.plan_conversion.node_processor

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference

/**
 * Describes how a multi-hop join candidate is formed — the lineage of the two-node join.
 *
 * Port of `metricflow.plan_conversion.node_processor.MultiHopJoinCandidateLineage`.
 *
 * Example: if `bridge_source` has the primary entity `account_id` and the foreign entity
 * `customer_id`, `customers_source` has primary entity `customer_id` and dimension `country`,
 * and `transactions_source` has the transaction's simple-metric input plus `account_id` as a
 * foreign entity, then the candidate lineage that produces the `country` dimension is
 * `first_node = bridge_source, second_node = customers_source`.
 */
data class MultiHopJoinCandidateLineage(
    val firstNodeToJoin: DataflowPlanNode,
    val secondNodeToJoin: DataflowPlanNode,
    val joinSecondNodeByEntity: EntityReference,
)

/**
 * A candidate dataflow node materialising a multi-hop join.
 *
 * Port of `metricflow.plan_conversion.node_processor.MultiHopJoinCandidate`. The
 * [nodeWithMultiHopElements] is the joined node ready to participate in the dataflow plan;
 * [lineage] records how it was built so dedupe can detect equivalent candidates.
 */
data class MultiHopJoinCandidate(
    val nodeWithMultiHopElements: DataflowPlanNode,
    val lineage: MultiHopJoinCandidateLineage,
)
