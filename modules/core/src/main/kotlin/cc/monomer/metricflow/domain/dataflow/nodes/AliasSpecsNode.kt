package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.InstanceSpec

/**
 * A mapping from an input spec to the output spec it should be renamed to.
 *
 * Port of `metricflow.dataflow.nodes.alias_specs.SpecToAlias`.
 */
data class SpecToAlias(val inputSpec: InstanceSpec, val outputSpec: InstanceSpec)

/**
 * Change the columns matching the key specs to match the value specs (renaming pass).
 *
 * Port of `metricflow.dataflow.nodes.alias_specs.AliasSpecsNode`.
 *
 * Note: Python declares this class `ABC` even though the codebase only constructs it directly —
 * the marker is decorative. We do not replicate the abstract-ness in Kotlin.
 */
class AliasSpecsNode(
    parentNode: DataflowPlanNode,
    /** Must have at least one entry. */
    val changeSpecs: List<SpecToAlias>,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    init {
        check(changeSpecs.isNotEmpty()) { "Must have at least one value in change_specs for AliasSpecsNode." }
    }

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String get() = "Change Column Aliases"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_ALIAS_SPECS_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("change_specs", changeSpecs)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitAliasSpecsNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is AliasSpecsNode && other.changeSpecs == changeSpecs

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): AliasSpecsNode {
        check(newParentNodes.size == 1) {
            "AliasSpecsNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return AliasSpecsNode(parentNode = newParentNodes[0], changeSpecs = changeSpecs)
    }
}
