package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Eliminates redundant table-alias qualifiers in column references when the surrounding
 * SELECT has only a single source. e.g.
 *
 * ```
 * SELECT b.foo                 SELECT foo
 * FROM (                  -->  FROM (
 *   SELECT a.foo FROM bar a       SELECT foo FROM bar a
 * ) b                          ) b
 * ```
 *
 * Port of `metricflow.sql.optimizer.table_alias_simplifier.SqlTableAliasSimplifier`. The
 * underlying [SqlPlanNodeVisitor] is [SqlTableAliasSimplifierVisitor].
 */
class SqlTableAliasSimplifier : SqlPlanOptimizer {
    override fun optimize(node: SqlPlanNode): SqlPlanNode = node.accept(SqlTableAliasSimplifierVisitor())
}

/**
 * The visitor that backs [SqlTableAliasSimplifier].
 *
 * Port of `SqlTableAliasSimplifierVisitor`. If a SELECT has no joins, the table alias can
 * be dropped from every column reference in the SELECT-list / GROUP BY / ORDER BY / WHERE
 * (because there's no ambiguity). With joins, this is unsafe so the visitor recurses into
 * sub-queries but leaves the current node's clauses unchanged.
 */
class SqlTableAliasSimplifierVisitor : SqlPlanNodeVisitor<SqlPlanNode> {

    override fun visitCteNode(node: SqlCteNode): SqlPlanNode =
        node.withNewSelect(node.selectStatement.accept(this))

    override fun visitSelectStatementNode(node: SqlSelectStatementNode): SqlPlanNode {
        val shouldSimplifyAliases = node.joinDescs.isEmpty()

        if (shouldSimplifyAliases) {
            return SqlSelectStatementNode.create(
                description = node.description,
                selectColumns = node.selectColumns.map { c ->
                    SqlSelectColumn(
                        expr = c.expr.rewrite(columnReplacements = null, shouldRenderTableAlias = false),
                        columnAlias = c.columnAlias,
                    )
                },
                fromSource = node.fromSource.accept(this),
                fromSourceAlias = node.fromSourceAlias,
                cteSources = node.cteSources.map { it.withNewSelect(it.selectStatement.accept(this)) },
                joinDescs = emptyList(),
                groupBys = node.groupBys.map { c ->
                    SqlSelectColumn(
                        expr = c.expr.rewrite(columnReplacements = null, shouldRenderTableAlias = false),
                        columnAlias = c.columnAlias,
                    )
                },
                orderBys = node.orderBys.map { o ->
                    SqlOrderByDescription(
                        expr = o.expr.rewrite(columnReplacements = null, shouldRenderTableAlias = false),
                        desc = o.desc,
                    )
                },
                where = node.where?.rewrite(columnReplacements = null, shouldRenderTableAlias = false),
                limit = node.limit,
                distinct = node.distinct,
            )
        }

        return SqlSelectStatementNode.create(
            description = node.description,
            selectColumns = node.selectColumns,
            fromSource = node.fromSource.accept(this),
            fromSourceAlias = node.fromSourceAlias,
            cteSources = node.cteSources.map { it.withNewSelect(it.selectStatement.accept(this)) },
            joinDescs = node.joinDescs.map { join ->
                SqlJoinDescription(
                    rightSource = join.rightSource.accept(this),
                    rightSourceAlias = join.rightSourceAlias,
                    onCondition = join.onCondition,
                    joinType = join.joinType,
                )
            },
            groupBys = node.groupBys,
            orderBys = node.orderBys,
            where = node.where,
            limit = node.limit,
            distinct = node.distinct,
        )
    }

    override fun visitTableNode(node: SqlTableNode): SqlPlanNode = node

    override fun visitQueryFromClauseNode(node: SqlSelectTextNode): SqlPlanNode = node

    override fun visitCreateTableAsNode(node: SqlCreateTableAsNode): SqlPlanNode =
        SqlCreateTableAsNode.create(
            sqlTable = node.sqlTable,
            parentNode = node.parentNode.accept(this),
        )
}
