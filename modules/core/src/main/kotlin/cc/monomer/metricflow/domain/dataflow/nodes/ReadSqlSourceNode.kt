package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.dataflow.support.SqlDataSet
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Source node where data from a SQL table or SQL query is read into the dataflow.
 *
 * Port of `metricflow.dataflow.nodes.read_sql_source.ReadSqlSourceNode`.
 *
 * The visitor dispatch is **`visitSourceNode`** (not `visitReadSqlSource…`) — preserving the
 * Python visitor's legacy name from when this was the only source variant.
 *
 * Note: source nodes have **no parents** by definition (this is the DAG entry point).
 */
class ReadSqlSourceNode(val dataSet: SqlDataSet) :
    DataflowPlanNode(parentNodes = emptyList()) {

    override val inputSemanticModel: SemanticModelReference? get() = dataSet.semanticModelReference

    override val description: String get() = "Read From $dataSet"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_READ_SQL_SOURCE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("data_set", dataSet)

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R = visitor.visitSourceNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean =
        other is ReadSqlSourceNode && other.dataSet == dataSet

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): ReadSqlSourceNode {
        check(newParentNodes.isEmpty()) {
            "ReadSqlSourceNode has no parents. Got: ${newParentNodes.size}"
        }
        return ReadSqlSourceNode(dataSet)
    }
}
