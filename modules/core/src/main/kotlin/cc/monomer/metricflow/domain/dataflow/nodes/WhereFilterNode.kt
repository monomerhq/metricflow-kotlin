package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Removes rows from the input by applying a `WHERE` clause.
 *
 * Port of `metricflow.dataflow.nodes.where_filter.WhereFilterNode`.
 *
 * The visitor dispatch is **`visitWhereConstraintNode`** — the legacy name preserved from
 * before the filter was renamed in Python.
 *
 * @property filterSpecs Specifications for the WHERE clause used to filter rows.
 * @property alwaysApply When `true`, the WHERE clause is applied even when the column it
 *   references does not appear in the output.
 */
class WhereFilterNode(
    parentNode: DataflowPlanNode,
    val filterSpecs: List<WhereFilterSpec>,
    val alwaysApply: Boolean,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    // Description deliberately omits the WHERE condition itself — rendering a bind-parameter
    // string here would cause "$1" placeholder leaks in DAG snapshots. See Python comment.
    override val description: String get() = "Filter Output with WHERE"

    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_WHERE_CONSTRAINT_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            for (spec in filterSpecs) add(DisplayedProperty("filter_spec", spec))
            if (alwaysApply) add(DisplayedProperty("All filters always applied:", alwaysApply))
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitWhereConstraintNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean {
        if (other !is WhereFilterNode) return false
        // Mirror Python: compare the SQL string and bind parameters per spec rather than the
        // full WhereFilterSpec (which includes the element-set view that's derivable from SQL).
        val otherWhere = other.filterSpecs.map { it.whereSql }
        val selfWhere = filterSpecs.map { it.whereSql }
        if (otherWhere != selfWhere) return false
        val otherBinds = other.filterSpecs.map { it.bindParameters }
        val selfBinds = filterSpecs.map { it.bindParameters }
        if (otherBinds != selfBinds) return false
        return other.alwaysApply == alwaysApply
    }

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): WhereFilterNode {
        check(newParentNodes.size == 1) {
            "WhereFilterNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return WhereFilterNode(
            parentNode = newParentNodes[0],
            filterSpecs = filterSpecs,
            alwaysApply = alwaysApply,
        )
    }
}
