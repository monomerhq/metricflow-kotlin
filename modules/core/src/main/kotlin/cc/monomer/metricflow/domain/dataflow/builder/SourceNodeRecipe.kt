package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.spec.LinkableSpecSet

/**
 * Recipe for building a dataflow plan branch that outputs simple-metric inputs and the
 * linkable instances they need.
 *
 * Port of `metricflow.dataflow.builder.source_node_recipe.SourceNodeRecipe`.
 *
 * The [DataflowPlanBuilder] queries the semantic graph to find a [sourceNode] (a
 * [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode] / metric-time
 * transform) plus a list of `JoinLinkableInstancesRecipe`s. The recipe is then turned into a
 * `JoinOnEntitiesNode` if any joins are needed.
 */
data class SourceNodeRecipe(
    val sourceNode: DataflowPlanNode,
    val requiredLocalLinkableSpecs: LinkableSpecSet,
    val joinLinkableInstancesRecipes: List<JoinLinkableInstancesRecipe>,
    val allLinkableSpecsRequiredForSourceNodes: LinkableSpecSet,
) {
    /** Convenience accessor: extract every [JoinDescription] from the recipes. */
    val joinTargets: List<JoinDescription>
        get() = joinLinkableInstancesRecipes.map { it.joinDescription }
}
