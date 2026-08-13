package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.ValidityWindowJoinDescription
import cc.monomer.metricflow.domain.dataflow.support.PartitionDimensionJoinDescription
import cc.monomer.metricflow.domain.dataflow.support.PartitionTimeDimensionJoinDescription
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.groupSpecsByType

/**
 * Recipe for joining a "node-to-join" (carrying linkable instances) onto the "left node".
 *
 * Port of
 * `metricflow.dataflow.builder.node_evaluator.JoinLinkableInstancesRecipe`.
 *
 * The recipe knows:
 *
 * - which node provides the additional linkable instances ([nodeToJoin]);
 * - which entity to join on ([joinOnEntity], or `null` for a cross-join);
 * - which linkable specs become satisfied by this join ([satisfiableLinkableSpecs]);
 * - the join type and any partition / validity-window constraints.
 *
 * [joinDescription] converts the recipe into the [JoinDescription] shape consumed by
 * `JoinOnEntitiesNode`, wrapping the `nodeToJoin` in a [SelectorNode] that keeps only the
 * columns needed for the join + the satisfiable specs (minus their first entity link).
 */
data class JoinLinkableInstancesRecipe(
    val nodeToJoin: DataflowPlanNode,
    val joinOnEntity: EntityReference?,
    val satisfiableLinkableSpecs: List<LinkableInstanceSpec>,
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

    /**
     * Convert this recipe to the [JoinDescription] shape used by `JoinOnEntitiesNode`. Port of
     * the `join_description` cached property.
     *
     * The descriptor wraps [nodeToJoin] in a [SelectorNode] keeping only the columns needed to:
     *
     * - join (the join entity + partition columns + validity-window dimensions), and
     * - render the satisfiable specs (with their first entity link stripped, since that link is
     *   resolved by the join itself).
     */
    val joinDescription: JoinDescription
        get() {
            val includeSpecs = mutableListOf<LinkableInstanceSpec>()

            // Sanity check: only metric_time may have zero entity links among satisfiable specs.
            check(
                satisfiableLinkableSpecs.all { spec ->
                    spec.entityLinks.isNotEmpty() || spec.elementName == METRIC_TIME_ELEMENT_NAME
                },
            )

            val aggregatedToElements = nodeToJoin.aggregatedToElements
            if (aggregatedToElements.isNotEmpty()) {
                includeSpecs.addAll(aggregatedToElements)
            } else {
                for (spec in satisfiableLinkableSpecs) {
                    if (spec.entityLinks.isNotEmpty()) {
                        includeSpecs.add(EntitySpec.fromReference(spec.entityLinks[0]))
                    }
                }
            }

            includeSpecs.addAll(joinOnPartitionDimensions.map { it.nodeToJoinDimensionSpec })
            includeSpecs.addAll(joinOnPartitionTimeDimensions.map { it.nodeToJoinTimeDimensionSpec })
            validityWindow?.let {
                includeSpecs.add(it.windowStartDimension)
                includeSpecs.add(it.windowEndDimension)
            }

            // `satisfiable_linkable_specs` describes what's satisfied **after** the join, so the
            // entity link has to be removed when filtering pre-join columns.
            includeSpecs.addAll(
                satisfiableLinkableSpecs.map { spec ->
                    if (spec.entityLinks.isNotEmpty()) spec.withoutFirstEntityLink() else spec
                },
            )

            val selector = SelectorNode(
                parentNode = nodeToJoin,
                includeSpecs = groupSpecsByType(includeSpecs).dedupe(),
                replaceDescription = null,
                distinct = false,
            )

            return JoinDescription(
                joinNode = selector,
                joinOnEntity = joinOnEntity,
                joinOnPartitionDimensions = joinOnPartitionDimensions,
                joinOnPartitionTimeDimensions = joinOnPartitionTimeDimensions,
                validityWindow = validityWindow,
                joinType = joinType,
            )
        }
}

/**
 * Verdict on whether a node can satisfy a set of required linkable specs.
 *
 * Port of
 * `metricflow.dataflow.builder.node_evaluator.LinkableInstanceSatisfiabilityEvaluation`.
 *
 * Returned by `NodeEvaluatorForLinkableInstances.evaluateNode`. Categorises specs into:
 *
 * - [localLinkableSpecs]: already present in the left node's output;
 * - [joinableLinkableSpecs]: satisfiable by joining one of the candidate nodes;
 * - [joinRecipes]: the actual recipes for those joins (one per joined node);
 * - [unjoinableLinkableSpecs]: cannot be satisfied either locally or via joins.
 */
data class LinkableInstanceSatisfiabilityEvaluation(
    val localLinkableSpecs: List<LinkableInstanceSpec>,
    val joinableLinkableSpecs: List<LinkableInstanceSpec>,
    val joinRecipes: List<JoinLinkableInstancesRecipe>,
    val unjoinableLinkableSpecs: List<LinkableInstanceSpec>,
)
