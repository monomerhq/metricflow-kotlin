package cc.monomer.metricflow.domain.sql.optimizer.column_pruning

import cc.monomer.metricflow.domain.sql.optimizer.SqlPlanOptimizer
import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Removes unnecessary columns from SELECT statements in the SQL query plan.
 *
 * Port of `metricflow.sql.optimizer.column_pruning.column_pruner.SqlColumnPrunerOptimizer`.
 *
 * Drives a two-phase pruning pass:
 *
 * 1. Build a `SqlCteAliasMappingLookup` so CTE references can be resolved unambiguously
 *    in the presence of alias shadowing (see
 *    [cc.monomer.metricflow.domain.sql.optimizer.column_pruning.SqlCteAliasMappingLookupBuilderVisitor]).
 * 2. Run [SqlMapRequiredColumnAliasesVisitor] to determine which columns each SELECT node
 *    actually needs (transitively from the plan's render node, which keeps all its
 *    columns).
 * 3. Run [SqlColumnPrunerVisitor], which rebuilds each SELECT keeping only the tagged
 *    columns.
 *
 * If the render-node's required columns cannot be determined (e.g. the root is a
 * [SqlSelectTextNode]), pruning is skipped and the original tree is returned.
 */
class SqlColumnPrunerOptimizer : SqlPlanOptimizer {

    override fun optimize(node: SqlPlanNode): SqlPlanNode {
        // All columns in the nearest SELECT node must be kept — otherwise the meaning of
        // the query changes.
        val requiredSelectColumns = node.nearestSelectColumns(SqlCteAliasMapping.EMPTY)
            ?: return node

        val cteBuilder = SqlCteAliasMappingLookupBuilderVisitor()
        node.accept(cteBuilder)

        val mapRequiredVisitor = SqlMapRequiredColumnAliasesVisitor(
            startNode = node,
            requiredColumnAliasesInStartNode = requiredSelectColumns.mapTo(mutableSetOf()) { it.columnAlias },
            cteAliasMappingLookup = cteBuilder.cteAliasMappingLookup,
        )
        node.accept(mapRequiredVisitor)

        val pruningVisitor = SqlColumnPrunerVisitor(mapRequiredVisitor.requiredColumnAliasMapping)
        return node.accept(pruningVisitor)
    }
}

/**
 * Rewrites SELECT statements to retain only the columns tagged as required.
 *
 * Port of `SqlColumnPrunerVisitor`. Distinct selects and group-by columns are always kept
 * (see [SqlMapRequiredColumnAliasesVisitor] for the tagging logic that pre-computes which
 * aliases survive at each node).
 */
class SqlColumnPrunerVisitor(
    private val requiredAliasMapping: NodeToColumnAliasMapping,
) : SqlPlanNodeVisitor<SqlPlanNode> {

    override fun visitSelectStatementNode(node: SqlSelectStatementNode): SqlPlanNode {
        val requiredColumnAliases = requiredAliasMapping.getAliases(node)
        if (requiredColumnAliases.isEmpty()) {
            // Defensive — should not happen for valid plans, but match Python's "log and
            // return original" rather than throwing.
            return node
        }

        val retainedSelectColumns = node.selectColumns.filter { it.columnAlias in requiredColumnAliases }

        return SqlSelectStatementNode.create(
            description = node.description,
            selectColumns = retainedSelectColumns,
            fromSource = node.fromSource.accept(this),
            fromSourceAlias = node.fromSourceAlias,
            cteSources = node.cteSources.map { it.withNewSelect(it.selectStatement.accept(this)) },
            joinDescs = node.joinDescs.map { it.withRightSource(it.rightSource.accept(this)) },
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

    override fun visitCteNode(node: SqlCteNode): SqlPlanNode =
        node.withNewSelect(node.selectStatement.accept(this))
}
