package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnAliasReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReplacements
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionTreeLineage
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlLogicalOperator
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Mutable workspace for rewriting the clauses of a SELECT statement during sub-query
 * reduction.
 *
 * Port of `metricflow.sql.optimizer.rewriting_sub_query_reducer.RewritableSqlClauses`.
 */
class RewritableSqlClauses(
    var selectColumns: List<SqlSelectColumn>,
    var wheres: List<SqlExpressionNode>,
    var groupBys: List<SqlSelectColumn>,
    var orderBys: List<SqlOrderByDescription>,
) {

    /** Apply [columnReplacements] to every expression in every clause. */
    fun rewrite(columnReplacements: SqlColumnReplacements) {
        selectColumns = selectColumns.map { c ->
            SqlSelectColumn(
                expr = c.expr.rewrite(columnReplacements, shouldRenderTableAlias = null),
                columnAlias = c.columnAlias,
            )
        }
        wheres = wheres.map { it.rewrite(columnReplacements, shouldRenderTableAlias = null) }
        groupBys = groupBys.map { c ->
            SqlSelectColumn(
                expr = c.expr.rewrite(columnReplacements, shouldRenderTableAlias = null),
                columnAlias = c.columnAlias,
            )
        }
        orderBys = orderBys.map { o ->
            SqlOrderByDescription(
                expr = o.expr.rewrite(columnReplacements, shouldRenderTableAlias = null),
                desc = o.desc,
            )
        }
    }

    /**
     * Combine [wheres] with [additionalWhereClauses] into a single WHERE expression. If
     * there's just one, return it directly; otherwise wrap with an AND. Returns null when
     * empty.
     */
    fun combineWheres(additionalWhereClauses: List<SqlExpressionNode>): SqlExpressionNode? {
        val all = wheres + additionalWhereClauses
        return when {
            all.size == 1 -> all[0]
            all.size > 1 -> SqlLogicalExpression.create(
                operator = SqlLogicalOperator.AND,
                args = all,
            )
            else -> null
        }
    }

    /** Returns true if any clause contains ambiguous expressions (string or column-alias). */
    val containsAmbiguousExprs: Boolean
        get() = selectColumns.any { it.expr.lineage.containsAmbiguousExprs } ||
            wheres.any { it.lineage.containsAmbiguousExprs } ||
            groupBys.any { it.expr.lineage.containsAmbiguousExprs } ||
            orderBys.any { it.expr.lineage.containsAmbiguousExprs }
}

/**
 * The main sub-query reduction visitor — collapses nested SELECTs by rewriting column
 * references in the outer query to refer to the inner sources directly.
 *
 * Port of `SqlRewritingSubQueryReducerVisitor`. The rules for when collapsing is safe are
 * concentrated in [currentNodeCanBeReduced] — see Python source for the full rationale.
 */
class SqlRewritingSubQueryReducerVisitor : SqlPlanNodeVisitor<SqlPlanNode> {

    private fun reduceParents(node: SqlSelectStatementNode): SqlSelectStatementNode =
        SqlSelectStatementNode.create(
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

    private fun statementContainsDifficultExpressions(node: SqlSelectStatementNode): Boolean {
        val lineages = mutableListOf<SqlExpressionTreeLineage>()
        for (c in node.selectColumns) lineages.add(c.expr.lineage)
        if (node.where != null) lineages.add(node.where!!.lineage)
        for (g in node.groupBys) lineages.add(g.expr.lineage)
        for (o in node.orderBys) lineages.add(o.expr.lineage)

        val combined = SqlExpressionTreeLineage.mergeIterable(lineages)
        return combined.containsStringExprs || combined.containsColumnAliasExprs
    }

    private fun selectColumnsContainStringExpressions(selectColumns: List<SqlSelectColumn>): Boolean {
        val combined = SqlExpressionTreeLineage.mergeIterable(selectColumns.map { it.expr.lineage })
        return combined.stringExprs.isNotEmpty()
    }

    private fun selectColumnsAreColumnReferences(selectColumns: List<SqlSelectColumn>): Boolean =
        selectColumns.all { it.expr.asColumnReferenceExpression != null }

    private fun selectColumnsWithWindowFunctions(selectColumns: List<SqlSelectColumn>): List<SqlSelectColumn> =
        selectColumns.filter { it.expr.asWindowFunctionExpression != null }

    private fun isSimpleSource(node: SqlSelectStatementNode): Boolean {
        for (selectColumn in node.selectColumns) {
            if (selectColumn.expr.lineage.containsStringExprs) return false
            if (selectColumn.expr.lineage.containsColumnAliasExprs) return false
            if (selectColumn.expr.lineage.containsAggregateExprs) return false
        }
        return node.joinDescs.isEmpty() &&
            node.groupBys.isEmpty() &&
            node.orderBys.isEmpty() &&
            node.limit == null &&
            node.where == null
    }

    private fun currentNodeCanBeReduced(node: SqlSelectStatementNode): Boolean {
        // If this node has joins, don't collapse — complex.
        if (node.joinDescs.isNotEmpty()) return false

        val fromClauseNode = node.fromSource.asSelectNode
        if (fromClauseNode != null && fromClauseNode.cteSources.isNotEmpty()) return false

        // Parent must be a SELECT statement.
        val fromSelectNode = node.fromSource.asSelectNode ?: return false

        // String / alias-reference expressions are not yet handled.
        if (statementContainsDifficultExpressions(node)) return false

        if (fromSelectNode.distinct) return false

        // Both having ORDER BY — skip for simplicity.
        if (node.orderBys.isNotEmpty() && fromSelectNode.orderBys.isNotEmpty()) return false

        // Both having GROUP BY — skip.
        if (fromSelectNode.groupBys.isNotEmpty() && node.groupBys.isNotEmpty()) return false

        // Verbose source-expression — skip for readability.
        if (fromSelectNode.selectColumns.any { it.expr.isVerbose }) return false

        // If a parent group-by isn't used in the current SELECT, don't reduce (would
        // change query meaning).
        val currentSelectColumnRefs = node.selectColumns
            .mapNotNull { it.expr.asColumnReferenceExpression?.colRef?.columnName }
            .toSet()
        var allParentGroupBysUsed = true
        for (groupBy in fromSelectNode.groupBys) {
            val matching = findMatchingSelect(groupBy.expr, fromSelectNode.selectColumns)
            if (matching != null && matching.columnAlias !in currentSelectColumnRefs) {
                allParentGroupBysUsed = false
            }
        }
        if (!allParentGroupBysUsed) return false

        // ORDER BYs must be column references / alias references that match a SELECT
        // column. `getMatchingColumnForOrderBy` throws on misconfiguration; the original
        // Python wraps a `try / except` with a truthiness check, so we treat any throw as
        // a "can't reduce" signal.
        for (orderBy in node.orderBys.ifEmpty { fromSelectNode.orderBys }) {
            try {
                getMatchingColumnForOrderBy(orderBy.expr, node.selectColumns)
            } catch (_: RuntimeException) {
                return false
            }
        }

        // Parent has GROUP BY and this has WHERE — WHERE could reference an aggregation.
        if (fromSelectNode.groupBys.isNotEmpty() && node.where != null) return false

        // Parent has GROUP BY but current select columns aren't all column references.
        if (fromSelectNode.groupBys.isNotEmpty() &&
            !selectColumnsAreColumnReferences(node.selectColumns)
        ) {
            return false
        }

        // GROUP BY references a window function in the parent — can't be reduced.
        val parentColumnAliasesWithWindow = selectColumnsWithWindowFunctions(fromSelectNode.selectColumns)
            .map { it.columnAlias }
            .toSet()
        if (node.groupBys.isNotEmpty()) {
            val hasWindowRef = node.groupBys.any { groupBy ->
                val asColumnRef = groupBy.expr.asColumnReferenceExpression
                val asStringExpr = groupBy.expr.asStringExpression
                groupBy.columnAlias in parentColumnAliasesWithWindow ||
                    (asColumnRef != null && asColumnRef.colRef.columnName in parentColumnAliasesWithWindow) ||
                    (asStringExpr != null && asStringExpr.sqlExpr in parentColumnAliasesWithWindow)
            }
            if (hasWindowRef) return false
        }

        // Parent contains string columns and current has GROUP BY — `1` in source SELECT
        // would be misinterpreted as position-based GROUP BY.
        if (node.groupBys.isNotEmpty() && selectColumnsContainStringExpressions(fromSelectNode.selectColumns)) {
            return false
        }

        return true
    }

    private fun getColumnReplacements(parentNode: SqlSelectStatementNode, parentNodeAlias: String): SqlColumnReplacements {
        val replacements = mutableMapOf<SqlColumnReference, SqlExpressionNode>()
        for (selectColumn in parentNode.selectColumns) {
            val columnReference = SqlColumnReference(
                tableAlias = parentNodeAlias,
                columnName = selectColumn.columnAlias,
            )
            replacements[columnReference] = selectColumn.expr
        }
        return SqlColumnReplacements(replacements)
    }

    private fun rewriteSelectColumns(
        oldSelectColumns: List<SqlSelectColumn>,
        columnReplacements: SqlColumnReplacements,
    ): List<SqlSelectColumn> =
        oldSelectColumns.map { c ->
            SqlSelectColumn(
                expr = c.expr.rewrite(columnReplacements, shouldRenderTableAlias = null),
                columnAlias = c.columnAlias,
            )
        }

    private fun rewriteWhere(
        columnReplacements: SqlColumnReplacements,
        nodeWhere: SqlExpressionNode?,
        parentNodeWhere: SqlExpressionNode?,
    ): SqlExpressionNode? {
        if (nodeWhere == null && parentNodeWhere == null) return null
        if (nodeWhere != null && parentNodeWhere == null) {
            return nodeWhere.rewrite(columnReplacements, shouldRenderTableAlias = null)
        }
        if (nodeWhere == null && parentNodeWhere != null) {
            return parentNodeWhere
        }
        return SqlLogicalExpression.create(
            operator = SqlLogicalOperator.AND,
            args = listOf(nodeWhere!!, parentNodeWhere!!),
        )
    }

    private fun findMatchingSelectColumn(
        colRef: SqlColumnReference,
        selectColumns: List<SqlSelectColumn>,
    ): SqlSelectColumn? {
        for (selectColumn in selectColumns) {
            val ref = selectColumn.expr.asColumnReferenceExpression
            if (ref != null && ref.colRef == colRef) return selectColumn
        }
        return null
    }

    private fun findMatchingSelectColumnFromAliasRefExpr(
        colAliasRefExpr: SqlColumnAliasReferenceExpression,
        selectColumns: List<SqlSelectColumn>,
    ): SqlSelectColumn? {
        for (selectColumn in selectColumns) {
            val ref = selectColumn.expr.asColumnReferenceExpression
            if (ref != null && ref.colRef.columnName == colAliasRefExpr.columnAlias) return selectColumn
        }
        return null
    }

    private fun rewriteNodeWithJoin(node: SqlSelectStatementNode): SqlSelectStatementNode {
        val fromSourceSelect = node.fromSource.asSelectNode
        val fromSourceAlias = node.fromSourceAlias

        val allSourceAliases = mutableListOf(fromSourceAlias)
        val sourceAliasSet = mutableSetOf(fromSourceAlias)

        if (fromSourceSelect != null) {
            allSourceAliases.add(fromSourceSelect.fromSourceAlias)
            sourceAliasSet.add(fromSourceSelect.fromSourceAlias)
        }

        for (joinDesc in node.joinDescs) {
            allSourceAliases.add(joinDesc.rightSourceAlias)
            sourceAliasSet.add(joinDesc.rightSourceAlias)
            val joinedNodeSelect = joinDesc.rightSource.asSelectNode
            if (joinedNodeSelect != null) {
                allSourceAliases.add(joinedNodeSelect.fromSourceAlias)
                sourceAliasSet.add(joinedNodeSelect.fromSourceAlias)
            }
        }

        // Duplicate aliases — bail out conservatively.
        if (allSourceAliases.size != sourceAliasSet.size) return node

        val clausesToRewrite = RewritableSqlClauses(
            selectColumns = node.selectColumns.toList(),
            wheres = if (node.where != null) listOf(node.where!!) else emptyList(),
            groupBys = node.groupBys.toList(),
            orderBys = node.orderBys.toList(),
        )

        if (clausesToRewrite.containsAmbiguousExprs) return node

        var newJoinDescs = mutableListOf<SqlJoinDescription>()
        val additionalWhereClauses = mutableListOf<SqlExpressionNode>()
        val columnReplacementsFromAllJoins = mutableListOf<SqlColumnReplacements>()

        for (joinDesc in node.joinDescs) {
            val joinSelectNode = joinDesc.rightSource.asSelectNode
            if (joinSelectNode == null ||
                !isSimpleSource(joinSelectNode) ||
                joinSelectNode.selectColumns.any { it.expr.isVerbose }
            ) {
                newJoinDescs.add(joinDesc)
                continue
            }

            val columnReplacements = getColumnReplacements(
                parentNode = joinSelectNode,
                parentNodeAlias = joinDesc.rightSourceAlias,
            )
            columnReplacementsFromAllJoins.add(columnReplacements)

            newJoinDescs.add(
                SqlJoinDescription(
                    rightSource = joinSelectNode.fromSource,
                    rightSourceAlias = joinSelectNode.fromSourceAlias,
                    onCondition = joinDesc.onCondition?.rewrite(columnReplacements, shouldRenderTableAlias = null),
                    joinType = joinDesc.joinType,
                ),
            )

            if (joinSelectNode.where != null) {
                additionalWhereClauses.add(joinSelectNode.where!!)
            }
            clausesToRewrite.rewrite(columnReplacements)
        }

        // The ON condition could reference columns from other joins; apply each
        // replacement to all join ON conditions.
        for (columnReplacements in columnReplacementsFromAllJoins) {
            newJoinDescs = newJoinDescs.map { x ->
                SqlJoinDescription(
                    rightSource = x.rightSource,
                    rightSourceAlias = x.rightSourceAlias,
                    onCondition = x.onCondition?.rewrite(columnReplacements, shouldRenderTableAlias = null),
                    joinType = x.joinType,
                )
            }.toMutableList()
        }

        val fromSourceIsSimple = fromSourceSelect != null && isSimpleSource(fromSourceSelect)
        var resolvedFromSource: SqlPlanNode = node.fromSource
        var resolvedFromSourceAlias: String = fromSourceAlias

        if (fromSourceSelect != null && fromSourceIsSimple) {
            val columnReplacements = getColumnReplacements(
                parentNode = fromSourceSelect,
                parentNodeAlias = node.fromSourceAlias,
            )

            if (fromSourceSelect.where != null) {
                additionalWhereClauses.add(fromSourceSelect.where!!)
            }

            clausesToRewrite.rewrite(columnReplacements)
            check(fromSourceSelect.joinDescs.isEmpty()) { "isSimpleSource ensures no joins." }
            resolvedFromSource = fromSourceSelect.fromSource
            resolvedFromSourceAlias = fromSourceSelect.fromSourceAlias

            newJoinDescs = newJoinDescs.map { x ->
                SqlJoinDescription(
                    rightSource = x.rightSource,
                    rightSourceAlias = x.rightSourceAlias,
                    onCondition = x.onCondition?.rewrite(columnReplacements, shouldRenderTableAlias = null),
                    joinType = x.joinType,
                )
            }.toMutableList()
        }

        return SqlSelectStatementNode.create(
            description = node.description,
            selectColumns = clausesToRewrite.selectColumns,
            fromSource = resolvedFromSource,
            fromSourceAlias = resolvedFromSourceAlias,
            cteSources = node.cteSources.map { it.withNewSelect(it.selectStatement.accept(this)) },
            joinDescs = newJoinDescs,
            groupBys = clausesToRewrite.groupBys,
            orderBys = clausesToRewrite.orderBys,
            where = clausesToRewrite.combineWheres(additionalWhereClauses),
            limit = node.limit,
            distinct = node.distinct,
        )
    }

    override fun visitCteNode(node: SqlCteNode): SqlPlanNode =
        SqlCteNode.create(
            // Mirror Python's `node.accept(self)` — recurse on the WHOLE CTE node (which
            // re-enters this visitor on the inner select).
            selectStatement = node.accept(this),
            cteAlias = node.cteAlias,
        )

    override fun visitSelectStatementNode(node: SqlSelectStatementNode): SqlPlanNode {
        val nodeWithReducedParents = reduceParents(node)

        if (nodeWithReducedParents.joinDescs.isNotEmpty()) {
            return rewriteNodeWithJoin(nodeWithReducedParents)
        }

        if (!currentNodeCanBeReduced(nodeWithReducedParents)) {
            return nodeWithReducedParents
        }

        val fromSourceSelectNode = nodeWithReducedParents.fromSource.asSelectNode
            ?: error("fromSourceSelectNode should be set as currentNodeCanBeReduced() returned true.")

        val columnReplacements = getColumnReplacements(
            parentNode = fromSourceSelectNode,
            parentNodeAlias = node.fromSourceAlias,
        )
        val newOrderBys = mutableListOf<SqlOrderByDescription>()
        val orderBysToUse = nodeWithReducedParents.orderBys.ifEmpty { fromSourceSelectNode.orderBys }
        if (orderBysToUse.isNotEmpty()) {
            for (orderByItem in orderBysToUse) {
                val matchingSelectColumn = getMatchingColumnForOrderBy(
                    orderByExpr = orderByItem.expr,
                    selectColumns = nodeWithReducedParents.selectColumns,
                )
                newOrderBys.add(
                    SqlOrderByDescription(
                        expr = SqlColumnAliasReferenceExpression.create(
                            columnAlias = matchingSelectColumn.columnAlias,
                        ),
                        desc = orderByItem.desc,
                    ),
                )
            }
        }

        // The limit should be the min of the two.
        var newLimit: Int? = nodeWithReducedParents.limit
        if (newLimit == null) {
            newLimit = fromSourceSelectNode.limit
        } else if (fromSourceSelectNode.limit != null) {
            newLimit = minOf(newLimit, fromSourceSelectNode.limit!!)
        }

        val newGroupBys: List<SqlSelectColumn> = when {
            node.groupBys.isNotEmpty() && fromSourceSelectNode.groupBys.isNotEmpty() ->
                throw IllegalStateException(
                    "Attempting to reduce sub-queries when this and the parent have GROUP BYs. " +
                        "This should have been prevented by currentNodeCanBeReduced()",
                )
            node.groupBys.isNotEmpty() ->
                rewriteSelectColumns(node.groupBys, columnReplacements)
            fromSourceSelectNode.groupBys.isNotEmpty() ->
                fromSourceSelectNode.groupBys
            else -> emptyList()
        }

        return SqlSelectStatementNode.create(
            description = listOf(fromSourceSelectNode.description, nodeWithReducedParents.description).joinToString("\n"),
            selectColumns = rewriteSelectColumns(
                oldSelectColumns = node.selectColumns,
                columnReplacements = columnReplacements,
            ),
            fromSource = fromSourceSelectNode.fromSource,
            fromSourceAlias = fromSourceSelectNode.fromSourceAlias,
            cteSources = node.cteSources.map { it.withNewSelect(it.selectStatement.accept(this)) },
            joinDescs = fromSourceSelectNode.joinDescs,
            groupBys = newGroupBys,
            orderBys = newOrderBys,
            where = rewriteWhere(
                columnReplacements = columnReplacements,
                nodeWhere = node.where,
                parentNodeWhere = fromSourceSelectNode.where,
            ),
            limit = newLimit,
            distinct = fromSourceSelectNode.distinct,
        )
    }

    private fun findMatchingSelect(
        expr: SqlExpressionNode,
        selectColumns: List<SqlSelectColumn>,
    ): SqlSelectColumn? {
        for (selectColumn in selectColumns) {
            if (selectColumn.expr.matches(expr)) return selectColumn
        }
        return null
    }

    private fun getMatchingColumnForOrderBy(
        orderByExpr: SqlExpressionNode,
        selectColumns: List<SqlSelectColumn>,
    ): SqlSelectColumn {
        val orderByColRefExpr = orderByExpr.asColumnReferenceExpression
        val orderByColAliasRefExpr = orderByExpr.asColumnAliasReferenceExpression
        val matching: SqlSelectColumn? = when {
            orderByColRefExpr != null -> findMatchingSelectColumn(orderByColRefExpr.colRef, selectColumns)
            orderByColAliasRefExpr != null ->
                findMatchingSelectColumnFromAliasRefExpr(orderByColAliasRefExpr, selectColumns)
            else -> throw IllegalStateException(
                "Expected a column reference or column-alias reference in ORDER BY but got: $orderByExpr",
            )
        }
        return matching ?: throw IllegalStateException(
            "Did not find matching select column for order by - this indicates internal misconfiguration.",
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

/**
 * Rewrites the GROUP BY clause to refer to column aliases instead of repeating the
 * select-column expression. Some engines (notably Trino) require this when grouping by an
 * expression that doesn't appear identically in the SELECT.
 *
 * Port of `SqlGroupByRewritingVisitor`. Driven by
 * [SqlRewritingSubQueryReducer.useColumnAliasInGroupBys].
 */
class SqlGroupByRewritingVisitor : SqlPlanNodeVisitor<SqlPlanNode> {

    private fun findMatchingSelect(
        expr: SqlExpressionNode,
        selectColumns: List<SqlSelectColumn>,
    ): SqlSelectColumn? {
        for (selectColumn in selectColumns) {
            if (selectColumn.expr.matches(expr)) return selectColumn
        }
        return null
    }

    override fun visitCteNode(node: SqlCteNode): SqlPlanNode =
        node.withNewSelect(node.selectStatement.accept(this))

    override fun visitSelectStatementNode(node: SqlSelectStatementNode): SqlPlanNode {
        val newGroupBys = mutableListOf<SqlSelectColumn>()
        for (groupBy in node.groupBys) {
            val matching = findMatchingSelect(groupBy.expr, node.selectColumns)
            if (matching != null) {
                newGroupBys.add(
                    SqlSelectColumn(
                        expr = SqlColumnAliasReferenceExpression.create(columnAlias = matching.columnAlias),
                        columnAlias = matching.columnAlias,
                    ),
                )
            } else {
                newGroupBys.add(groupBy)
            }
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
            groupBys = newGroupBys,
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

/**
 * Eliminates redundant sub-queries by rewriting their column references in the enclosing
 * SELECT.
 *
 * Port of `metricflow.sql.optimizer.rewriting_sub_query_reducer.SqlRewritingSubQueryReducer`.
 *
 * Example transformation:
 * ```
 * SELECT b.col0 AS foo                           SELECT SUM(a.col0) AS foo
 * FROM (                                  -->    FROM table0 a
 *   SELECT SUM(a.col0) AS bar                    GROUP BY foo
 *   FROM table0 a
 * ) b
 * GROUP BY b.col0
 * ```
 *
 * The reducer is conservative — many corner cases (string expressions, parent GROUP BY
 * with child WHERE, window functions referenced in GROUP BY, …) cause the pass to leave
 * the original sub-query in place. See the `currentNodeCanBeReduced` implementation for
 * the full list of bail-out rules.
 *
 * The [useColumnAliasInGroupBys] flag chains a final [SqlGroupByRewritingVisitor] that
 * rewrites GROUP BY to refer to SELECT-list aliases — needed by engines like Trino.
 */
class SqlRewritingSubQueryReducer(
    private val useColumnAliasInGroupBys: Boolean,
) : SqlPlanOptimizer {

    override fun optimize(node: SqlPlanNode): SqlPlanNode {
        val result = node.accept(SqlRewritingSubQueryReducerVisitor())
        if (useColumnAliasInGroupBys) {
            return result.accept(SqlGroupByRewritingVisitor())
        }
        return result
    }
}
