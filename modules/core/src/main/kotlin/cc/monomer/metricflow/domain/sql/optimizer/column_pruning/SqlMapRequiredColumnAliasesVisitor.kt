package cc.monomer.metricflow.domain.sql.optimizer.column_pruning

import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionTreeLineage
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Traverses the SQL plan, tagging each SELECT with the column aliases that downstream
 * consumers actually require.
 *
 * Port of
 * `metricflow.sql.optimizer.column_pruning.required_column_aliases.SqlMapRequiredColumnAliasesVisitor`.
 *
 * The traversal propagates required aliases from a child SELECT to its parents (sources in
 * FROM / JOIN) so that the subsequent [SqlColumnPrunerVisitor] knows which SELECT-list
 * columns can be dropped from each node without changing the meaning of the query.
 *
 * For example, given the query:
 * ```
 * -- SELECT node_id="select_0"
 * SELECT source_0.col_0 AS col_0_renamed
 * FROM (
 *     -- SELECT node_id="select_1"
 *     SELECT
 *         example_table.col_0
 *         example_table.col_1
 *     FROM example_table_0
 * ) source_0
 * ```
 * the mapping records `{select_0: {col_0_renamed}, select_1: {col_0}}` so `col_1` can be
 * pruned from `select_1`.
 *
 * Distinct SELECTs and GROUP BYs trigger "keep all columns" or "keep group-by columns" to
 * preserve query semantics.
 */
class SqlMapRequiredColumnAliasesVisitor(
    startNode: SqlPlanNode,
    requiredColumnAliasesInStartNode: Set<String>,
    private val cteAliasMappingLookup: SqlCteAliasMappingLookup,
) : SqlPlanNodeVisitor<Unit> {

    private val currentMapping: NodeToColumnAliasMapping = NodeToColumnAliasMapping().also {
        it.addAliases(startNode, requiredColumnAliasesInStartNode)
    }

    /** Return the column aliases required at each node as determined after traversal. */
    val requiredColumnAliasMapping: NodeToColumnAliasMapping get() = currentMapping

    private fun searchForExpressions(
        selectNode: SqlSelectStatementNode,
        prunedSelectColumns: List<SqlSelectColumn>,
    ): SqlExpressionTreeLineage {
        val lineages = mutableListOf<SqlExpressionTreeLineage>()

        for (selectColumn in prunedSelectColumns) {
            lineages.add(selectColumn.expr.lineage)
        }

        for (joinDescription in selectNode.joinDescs) {
            val onCondition = joinDescription.onCondition
            if (onCondition != null) {
                lineages.add(onCondition.lineage)
            }
        }

        for (groupBy in selectNode.groupBys) {
            lineages.add(groupBy.expr.lineage)
        }

        for (orderBy in selectNode.orderBys) {
            lineages.add(orderBy.expr.lineage)
        }

        if (selectNode.where != null) {
            lineages.add(selectNode.where!!.lineage)
        }

        return SqlExpressionTreeLineage.mergeIterable(lineages)
    }

    override fun visitCteNode(node: SqlCteNode) {
        val selectStatement = node.selectStatement
        // Copy the tagged aliases from the CTE to the SELECT since when visiting a SELECT,
        // the CTE node (not the inner SELECT) was tagged with the required aliases.
        val required = currentMapping.getAliases(node)
        currentMapping.addAliases(selectStatement, required)
        selectStatement.accept(this)
    }

    private fun visitParents(node: SqlPlanNode) {
        for (parentNode in node.parentNodes) {
            parentNode.accept(this)
        }
    }

    private fun mapRequiredColumnAliasesInPotentialCte(
        cteAliasMapping: SqlCteAliasMapping,
        tableName: String,
        columnAliases: Set<String>,
    ) {
        val cteNode = cteAliasMapping.getCteNodeForAlias(tableName)
        if (cteNode != null) {
            currentMapping.addAliases(cteNode, columnAliases)
            // `visitCteNode` propagates required aliases to all CTEs this CTE depends on.
            cteNode.accept(this)
        }
    }

    override fun visitSelectStatementNode(node: SqlSelectStatementNode) {
        val cteAliasMapping = cteAliasMappingLookup.getCteAliasMapping(node)

        val initialRequired = currentMapping.getAliases(node)

        // For DISTINCT SELECT, all SELECT columns are required.
        val updatedRequired = initialRequired.toMutableSet()
        if (node.distinct) {
            updatedRequired.addAll(node.selectColumns.map { it.columnAlias })
        }
        // GROUP BY columns must be retained.
        updatedRequired.addAll(node.groupBys.map { it.columnAlias })

        // Re-tag for the next visitor pass.
        currentMapping.addAliases(node, updatedRequired)

        val requiredSelectColumnsInThisNode = node.selectColumns.filter { it.columnAlias in updatedRequired }

        if (requiredSelectColumnsInThisNode.isEmpty()) {
            throw IllegalStateException(
                "No columns are required in this node - this indicates a bug in this visitor or in the inputs.",
            )
        }

        val exprsUsed = searchForExpressions(node, requiredSelectColumnsInThisNode)

        // If any string expr has unknown `usedColumns`, conservatively keep every column.
        if (exprsUsed.stringExprs.any { it.usedColumns == null }) {
            val nodesToRetainAllColumns = mutableListOf<SqlPlanNode>(node.fromSource)
            for (joinDesc in node.joinDescs) {
                nodesToRetainAllColumns.add(joinDesc.rightSource)
            }
            for (n in nodesToRetainAllColumns) {
                val nearest = n.nearestSelectColumns(cteAliasMapping)
                for (selectColumn in nearest.orEmpty()) {
                    currentMapping.addAlias(n, selectColumn.columnAlias)
                }
            }
            visitParents(node)
            return
        }

        // Map source aliases → column aliases required from that source.
        val sourceAliasToRequired = mutableMapOf<String, MutableSet<String>>()
        for (columnReferenceExpr in exprsUsed.columnReferenceExprs) {
            val columnReference = columnReferenceExpr.colRef
            sourceAliasToRequired
                .getOrPut(columnReference.tableAlias) { mutableSetOf() }
                .add(columnReference.columnName)
        }

        // Tag the FROM source.
        sourceAliasToRequired[node.fromSourceAlias]?.let { aliases ->
            currentMapping.addAliases(node.fromSource, aliases)
            val tableNode = node.fromSource.asSqlTableNode
            if (tableNode != null) {
                mapRequiredColumnAliasesInPotentialCte(
                    cteAliasMapping = cteAliasMapping,
                    tableName = tableNode.sqlTable.tableName,
                    columnAliases = aliases,
                )
            }
        }
        // Tag each JOIN's right source.
        for (joinDesc in node.joinDescs) {
            sourceAliasToRequired[joinDesc.rightSourceAlias]?.let { aliases ->
                currentMapping.addAliases(joinDesc.rightSource, aliases)
                val tableNode = joinDesc.rightSource.asSqlTableNode
                if (tableNode != null) {
                    mapRequiredColumnAliasesInPotentialCte(
                        cteAliasMapping = cteAliasMapping,
                        tableName = tableNode.sqlTable.tableName,
                        columnAliases = aliases,
                    )
                }
            }
        }

        // Find unqualified column references (string exprs with known cols, or
        // `SqlColumnAliasReferenceExpression`). Assume those columns are required from
        // every source — without table schema knowledge we can't tell which source they
        // originate from.
        val columnAliasesToRetain = mutableSetOf<String>()
        for (stringExpr in exprsUsed.stringExprs) {
            val used = stringExpr.usedColumns
            if (used != null) columnAliasesToRetain.addAll(used)
        }
        for (unqualified in exprsUsed.columnAliasReferenceExprs) {
            columnAliasesToRetain.add(unqualified.columnAlias)
        }

        val nodesToRetainCols = buildList {
            add(node.fromSource)
            for (joinDesc in node.joinDescs) add(joinDesc.rightSource)
        }
        for (n in nodesToRetainCols) {
            currentMapping.addAliases(n, columnAliasesToRetain)
            val tableNode = n.asSqlTableNode
            if (tableNode != null && tableNode.sqlTable.schemaName == null) {
                mapRequiredColumnAliasesInPotentialCte(
                    cteAliasMapping = cteAliasMapping,
                    tableName = tableNode.sqlTable.tableName,
                    columnAliases = columnAliasesToRetain,
                )
            }
        }

        visitParents(node)
    }

    override fun visitTableNode(node: SqlTableNode) {
        // Pruning cannot apply to a literal table — no SELECT columns here.
    }

    override fun visitQueryFromClauseNode(node: SqlSelectTextNode) {
        // Pruning cannot be done on arbitrary user-provided SQL.
    }

    override fun visitCreateTableAsNode(node: SqlCreateTableAsNode) {
        visitParents(node)
    }
}
