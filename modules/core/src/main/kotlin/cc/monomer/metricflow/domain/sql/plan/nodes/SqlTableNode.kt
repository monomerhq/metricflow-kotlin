package cc.monomer.metricflow.domain.sql.plan.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * A SQL table reference that can appear in the `FROM` clause or in a `JOIN`.
 *
 * Port of `metricflow.sql.sql_table_node.SqlTableNode`.
 */
class SqlTableNode(val sqlTable: SqlTable) : SqlPlanNode(emptyList()) {

    override val description: String get() = "Read from ${sqlTable.sql}"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_PLAN_TABLE_FROM_CLAUSE_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("table_id", sqlTable.sql)

    override fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R = visitor.visitTableNode(this)

    override val asSelectNode: SqlSelectStatementNode? get() = null
    override val asSqlTableNode: SqlTableNode? get() = this

    /**
     * If this table actually points at a CTE (no schema, alias matches a CTE name),
     * defer to the CTE's `nearestSelectColumns`.
     */
    override fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn>? {
        if (sqlTable.schemaName == null) {
            val cteNode = cteSourceMapping.getCteNodeForAlias(sqlTable.tableName)
            if (cteNode != null) return cteNode.nearestSelectColumns(cteSourceMapping)
        }
        return null
    }

    override fun copyNode(): SqlTableNode = create(sqlTable = sqlTable)

    companion object {
        fun create(sqlTable: SqlTable): SqlTableNode = SqlTableNode(sqlTable)
    }
}
