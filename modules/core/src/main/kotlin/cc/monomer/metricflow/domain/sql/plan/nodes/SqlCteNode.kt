package cc.monomer.metricflow.domain.sql.plan.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * A single common table expression — `<cte_alias> AS (<select_statement>)`.
 *
 * Port of `metricflow.sql.sql_cte_node.SqlCteNode`.
 */
class SqlCteNode(
    val selectStatement: SqlPlanNode,
    val cteAlias: String,
) : SqlPlanNode(listOf(selectStatement)) {

    override val description: String get() = "CTE"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_PLAN_COMMON_TABLE_EXPRESSION_ID_PREFIX

    override fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R = visitor.visitCteNode(this)

    override val asSelectNode: SqlSelectStatementNode? get() = null
    override val asSqlTableNode: SqlTableNode? get() = null

    /** Return a node with the same alias but a new SELECT statement. */
    fun withNewSelect(newSelectStatement: SqlPlanNode): SqlCteNode =
        create(newSelectStatement, cteAlias)

    override fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn>? =
        selectStatement.nearestSelectColumns(cteSourceMapping)

    override fun copyNode(): SqlCteNode = create(selectStatement.copyNode(), cteAlias)

    companion object {
        fun create(selectStatement: SqlPlanNode, cteAlias: String): SqlCteNode =
            SqlCteNode(selectStatement, cteAlias)
    }
}
