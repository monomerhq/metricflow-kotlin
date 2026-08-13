package cc.monomer.metricflow.domain.sql.plan.nodes

import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn

/**
 * A raw SQL `SELECT` query (string) that can appear in the `FROM` clause as a subquery.
 *
 * Port of `metricflow.sql.sql_select_text_node.SqlSelectTextNode`. Used when the
 * dataflow→SQL converter needs to wrap an arbitrary text fragment (the engine doesn't
 * know its structure, so column pruning is disabled — `nearestSelectColumns` returns null).
 */
class SqlSelectTextNode(val selectQuery: String) : SqlPlanNode(emptyList()) {

    override val description: String get() = "Read From a Select Query"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_PLAN_QUERY_FROM_CLAUSE_ID_PREFIX

    override fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R =
        visitor.visitQueryFromClauseNode(this)

    override val asSelectNode: SqlSelectStatementNode? get() = null
    override val asSqlTableNode: SqlTableNode? get() = null

    override fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn>? = null

    override fun copyNode(): SqlSelectTextNode = create(selectQuery)

    companion object {
        fun create(selectQuery: String): SqlSelectTextNode = SqlSelectTextNode(selectQuery)
    }
}
