package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpecSet

/**
 * Selectively passes elements from the input to the output (i.e. column projection).
 *
 * Port of `metricflow.dataflow.nodes.filter_elements.SelectorNode`. Despite the Python filename
 * ("filter_elements"), this is a SELECT-like projection — the rename of the **rows** is done
 * by [WhereFilterNode]. The visitor dispatch is `visit_selector_node`.
 *
 * @property includeSpecs The specs of elements to pass through.
 * @property replaceDescription Override for [description]. When non-null, displayedProperties
 *   also drop the include-specs/distinct details (matching Python's behaviour).
 * @property distinct If `true`, output only distinct values for the selected specs.
 */
class SelectorNode(
    parentNode: DataflowPlanNode,
    val includeSpecs: InstanceSpecSet,
    val replaceDescription: String?,
    val distinct: Boolean,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    override val description: String
        get() = replaceDescription ?: run {
            // Mirror Python: render the include-specs as their resolved column names with
            // Python-style list pretty-printing (single-quoted strings, comma-space separators).
            val resolver = DEFAULT_RESOLVER
            val names = includeSpecs.allSpecs.map { resolver.resolveSpec(it).columnName }
            "Select: ${names.joinToString(prefix = "[", postfix = "]") { "'$it'" }}"
        }

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_SELECTOR_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            if (replaceDescription == null) {
                for (spec in includeSpecs.allSpecs) add(DisplayedProperty("include_spec", spec))
                add(DisplayedProperty("distinct", distinct))
            }
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitSelectorNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is SelectorNode &&
            other.includeSpecs == includeSpecs &&
            other.distinct == distinct

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): SelectorNode {
        check(newParentNodes.size == 1) {
            "SelectorNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return SelectorNode(
            parentNode = newParentNodes[0],
            includeSpecs = includeSpecs,
            replaceDescription = replaceDescription,
            distinct = distinct,
        )
    }

    companion object {
        /**
         * Mirror Python's `DunderColumnAssociationResolver()` no-arg default. The Python class
         * defaults `dunder_prefix_simple_metric_inputs=True`; the Kotlin port requires the flag
         * be explicit at call sites (see [DunderColumnAssociationResolver] KDoc) but the
         * description-only resolver here is a stable cosmetic-rendering shortcut.
         */
        private val DEFAULT_RESOLVER = DunderColumnAssociationResolver(
            dunderPrefixSimpleMetricInputs = true,
        )
    }
}
