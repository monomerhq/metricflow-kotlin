package cc.monomer.metricflow.domain.sql.plan.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode

/**
 * Describes a single `JOIN` clause inside a [SqlSelectStatementNode].
 *
 * Port of `metricflow.sql.sql_select_node.SqlJoinDescription`.
 */
data class SqlJoinDescription(
    val rightSource: SqlPlanNode,
    val rightSourceAlias: String,
    val joinType: SqlJoinType,
    val onCondition: SqlExpressionNode?,
) {
    /** Return a copy with [rightSource] replaced. */
    fun withRightSource(newRightSource: SqlPlanNode): SqlJoinDescription =
        copy(rightSource = newRightSource)
}

/**
 * A single `ORDER BY` term inside a [SqlSelectStatementNode].
 *
 * Port of `metricflow.sql.sql_select_node.SqlOrderByDescription`.
 */
data class SqlOrderByDescription(val expr: SqlExpressionNode, val desc: Boolean)

/**
 * The central plan node — represents a `SELECT ... FROM ... [JOIN ...] [WHERE ...] [GROUP BY ...]
 * [ORDER BY ...] [LIMIT ...]` statement.
 *
 * Port of `metricflow.sql.sql_select_node.SqlSelectStatementNode`.
 *
 * Parent-node ordering (important for visitor traversal) mirrors Python exactly:
 * `from_source` first, then `join_descs[i].right_source`, then `cte_sources`.
 */
class SqlSelectStatementNode(
    private val customDescription: String,
    val selectColumns: List<SqlSelectColumn>,
    val fromSource: SqlPlanNode,
    val fromSourceAlias: String,
    val cteSources: List<SqlCteNode>,
    val joinDescs: List<SqlJoinDescription>,
    val groupBys: List<SqlSelectColumn>,
    val orderBys: List<SqlOrderByDescription>,
    val where: SqlExpressionNode?,
    val limit: Int?,
    val distinct: Boolean,
) : SqlPlanNode(
    parentNodes = buildList {
        add(fromSource)
        for (join in joinDescs) add(join.rightSource)
        addAll(cteSources)
    },
) {

    override val description: String get() = customDescription
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_PLAN_SELECT_STATEMENT_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties +
            selectColumns.mapIndexed { i, c -> DisplayedProperty("col$i", c) } +
            DisplayedProperty("from_source", fromSource) +
            joinDescs.mapIndexed { i, j -> DisplayedProperty("join_$i", j) } +
            groupBys.mapIndexed { i, g -> DisplayedProperty("group_by$i", g) } +
            DisplayedProperty("where", where) +
            orderBys.mapIndexed { i, o -> DisplayedProperty("order_by$i", o) } +
            DisplayedProperty("distinct", distinct)

    override fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R =
        visitor.visitSelectStatementNode(this)

    override val asSelectNode: SqlSelectStatementNode? get() = this
    override val asSqlTableNode: SqlTableNode? get() = null

    override fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn> =
        selectColumns

    override fun copyNode(): SqlSelectStatementNode = create(
        description = customDescription,
        selectColumns = selectColumns,
        fromSource = fromSource.copyNode(),
        fromSourceAlias = fromSourceAlias,
        cteSources = cteSources.map { it.copyNode() },
        joinDescs = joinDescs.map { it.withRightSource(it.rightSource.copyNode()) },
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )

    /** Return a copy with the select columns replaced. */
    fun withSelectColumns(selectColumns: List<SqlSelectColumn>): SqlSelectStatementNode = create(
        description = customDescription,
        selectColumns = selectColumns,
        fromSource = fromSource,
        fromSourceAlias = fromSourceAlias,
        cteSources = cteSources,
        joinDescs = joinDescs,
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )

    /** Return a copy with the WHERE clause replaced. */
    fun withWhereClause(where: SqlExpressionNode?): SqlSelectStatementNode = create(
        description = customDescription,
        selectColumns = selectColumns,
        fromSource = fromSource,
        fromSourceAlias = fromSourceAlias,
        cteSources = cteSources,
        joinDescs = joinDescs,
        groupBys = groupBys,
        orderBys = orderBys,
        where = where,
        limit = limit,
        distinct = distinct,
    )

    companion object {
        fun create(
            description: String,
            selectColumns: List<SqlSelectColumn>,
            fromSource: SqlPlanNode,
            fromSourceAlias: String,
            cteSources: List<SqlCteNode>,
            joinDescs: List<SqlJoinDescription>,
            groupBys: List<SqlSelectColumn>,
            orderBys: List<SqlOrderByDescription>,
            where: SqlExpressionNode?,
            limit: Int?,
            distinct: Boolean,
        ): SqlSelectStatementNode = SqlSelectStatementNode(
            customDescription = description,
            selectColumns = selectColumns,
            fromSource = fromSource,
            fromSourceAlias = fromSourceAlias,
            cteSources = cteSources,
            joinDescs = joinDescs,
            groupBys = groupBys,
            orderBys = orderBys,
            where = where,
            limit = limit,
            distinct = distinct,
        )
    }
}
