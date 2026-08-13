package cc.monomer.metricflow.domain.sql.render

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.common.util.mfIndent
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlSelectColumn
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlOrderByDescription
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * The ANSI-SQL default implementation of [SqlPlanRenderer]. Dialect renderers (W6) extend
 * this open class and override either the visit methods or the smaller per-section
 * helpers.
 *
 * Port of `metricflow.sql.render.sql_plan_renderer.DefaultSqlPlanRenderer`. We replicate
 * the Python class's section-by-section structure (description, CTEs, SELECT, FROM, JOINs,
 * WHERE, GROUP BY, ORDER BY, LIMIT) — each section is a `protected open` method so a
 * dialect can override just one piece (e.g. BigQuery overrides FROM rendering to support
 * its dotted database.schema.table syntax).
 */
open class DefaultSqlPlanRenderer : SqlPlanRenderer {

    /** The default expression renderer; dialects override [exprRenderer] to swap it out. */
    protected open val defaultExprRenderer: SqlExpressionRenderer = DefaultSqlExpressionRenderer()

    override val exprRenderer: SqlExpressionRenderer get() = defaultExprRenderer

    // ---------- Section helpers ----------

    /** Render the description section as a SQL comment, or null if [description] is empty. */
    protected open fun renderDescriptionSection(description: String): SqlPlanRenderResult? {
        if (description.isEmpty()) return null
        val lines = description.split("\n").filter { it.isNotEmpty() }.map { "-- $it" }
        return SqlPlanRenderResult(
            sql = lines.joinToString("\n"),
            bindParameterSet = SqlBindParameterSet.EMPTY,
        )
    }

    /** Render `WITH <alias> AS (<select>)`, ..., or null if [cteNodes] is empty. */
    protected open fun renderCteSections(cteNodes: List<SqlCteNode>): SqlPlanRenderResult? {
        if (cteNodes.isEmpty()) return null

        val cteResults = cteNodes.map { visitCteNode(it) }

        return SqlPlanRenderResult(
            sql = "WITH " + cteResults.joinToString("\n, ") { it.sql + "\n" },
            bindParameterSet = Mergeable.mergeIterable(
                cteResults.map { it.bindParameterSet },
                SqlBindParameterSet.EMPTY,
            ),
        )
    }

    /**
     * Render the SELECT-columns section. If [numParents] is 1, "src.col AS col" collapses
     * to just "src.col" — but only in the no-join case to avoid SQLite ambiguous-column
     * errors.
     */
    protected open fun renderSelectColumnsSection(
        selectColumns: List<SqlSelectColumn>,
        numParents: Int,
        distinct: Boolean,
    ): SqlPlanRenderResult {
        var params = SqlBindParameterSet.EMPTY
        val lines = mutableListOf<String>()
        lines.add(if (distinct) "SELECT DISTINCT" else "SELECT")

        var firstColumn = true
        for (selectColumn in selectColumns) {
            val exprRendered = exprRenderer.renderSqlExpr(selectColumn.expr)
            params = params.merge(exprRendered.bindParameterSet)

            var columnSelectStr = "${exprRendered.sql} AS ${selectColumn.columnAlias}"

            // Collapse "src.foo AS foo" to "src.foo" when there are no joins (to avoid
            // SQLite ambiguous-column errors on JOIN ambiguity).
            if (numParents <= 1) {
                val columnRefExpr = selectColumn.expr.asColumnReferenceExpression
                if (columnRefExpr != null && columnRefExpr.colRef.columnName == selectColumn.columnAlias) {
                    columnSelectStr = exprRendered.sql
                }
            }

            val indented = if (firstColumn) {
                firstColumn = false
                mfIndent(columnSelectStr, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT)
            } else {
                mfIndent(", $columnSelectStr", indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT)
            }
            lines.add(indented)
        }

        return SqlPlanRenderResult(sql = lines.joinToString("\n"), bindParameterSet = params)
    }

    /**
     * Render the aliased table expression. If [alias] equals [tableSql], the alias is
     * omitted. e.g. `mydb.bookings AS b` vs `bookings` (no alias when alias == table).
     */
    protected open fun renderAliasedTableExpression(tableSql: String, alias: String): String =
        if (tableSql == alias) tableSql else "$tableSql $alias"

    /** Render the FROM section. Sub-queries are wrapped in `FROM ( ... ) <alias>`. */
    protected open fun renderFromSection(
        fromSource: SqlPlanNode,
        fromSourceAlias: String,
    ): SqlPlanRenderResult {
        val asTable = fromSource.asSqlTableNode
        if (asTable != null) {
            val rendered = asTable.accept(this)
            return SqlPlanRenderResult(
                sql = "FROM " + renderAliasedTableExpression(rendered.sql, fromSourceAlias),
                bindParameterSet = rendered.bindParameterSet,
            )
        }

        val rendered = renderNode(fromSource)
        val sql = buildString {
            append("FROM (\n")
            append(mfIndent(rendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT))
            append("\n) ")
            append(fromSourceAlias)
        }
        return SqlPlanRenderResult(sql = sql, bindParameterSet = rendered.bindParameterSet)
    }

    /** Render the JOIN clauses (zero or more). */
    protected open fun renderJoinsSection(
        joinDescriptions: List<SqlJoinDescription>,
    ): SqlPlanRenderResult? {
        if (joinDescriptions.isEmpty()) return null

        var params = SqlBindParameterSet.EMPTY
        val joinLines = mutableListOf<String>()

        for (joinDesc in joinDescriptions) {
            val rightRendered = renderNode(joinDesc.rightSource)
            params = params.merge(rightRendered.bindParameterSet)

            val onConditionRendered: SqlExpressionRenderResult? = joinDesc.onCondition?.let {
                val rendered = exprRenderer.renderSqlExpr(it)
                params = params.merge(rendered.bindParameterSet)
                rendered
            }

            if (joinDesc.rightSource.asSqlTableNode != null) {
                joinLines.add(joinDesc.joinType.sql)
                joinLines.add(
                    mfIndent(
                        renderAliasedTableExpression(rightRendered.sql, joinDesc.rightSourceAlias),
                        indentLevel = 1,
                        indentPrefix = SqlRenderingConstants.INDENT,
                    ),
                )
            } else {
                joinLines.add("${joinDesc.joinType.sql} (")
                joinLines.add(mfIndent(rightRendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT))
                joinLines.add(") ${joinDesc.rightSourceAlias}")
            }

            if (onConditionRendered != null) {
                joinLines.add("ON")
                joinLines.add(
                    mfIndent(onConditionRendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT),
                )
            }
        }

        return SqlPlanRenderResult(sql = joinLines.joinToString("\n"), bindParameterSet = params)
    }

    /** Render the WHERE clause, or null if [whereExpression] is null. */
    protected open fun renderWhere(whereExpression: SqlExpressionNode?): SqlPlanRenderResult? {
        if (whereExpression == null) return null
        val rendered = exprRenderer.renderSqlExpr(whereExpression)
        return SqlPlanRenderResult(
            sql = "WHERE ${rendered.sql}",
            bindParameterSet = rendered.bindParameterSet,
        )
    }

    /** Render the GROUP BY section, or null if [groupByColumns] is empty. */
    protected open fun renderGroupBySection(
        groupByColumns: List<SqlSelectColumn>,
    ): SqlPlanRenderResult? {
        if (groupByColumns.isEmpty()) return null

        val lines = mutableListOf<String>()
        var params = SqlBindParameterSet.EMPTY
        var first = true
        for (groupByColumn in groupByColumns) {
            val rendered = exprRenderer.renderGroupByExpr(groupByColumn)
            params = params.merge(rendered.bindParameterSet)
            if (first) {
                first = false
                lines.add("GROUP BY")
                lines.add(mfIndent(rendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT))
            } else {
                lines.add(mfIndent(", ${rendered.sql}", indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT))
            }
        }

        return SqlPlanRenderResult(sql = lines.joinToString("\n"), bindParameterSet = params)
    }

    /** Render the ORDER BY section, or null if [orderBys] is empty. */
    protected open fun renderOrderBySection(
        orderBys: List<SqlOrderByDescription>,
    ): SqlPlanRenderResult? {
        if (orderBys.isEmpty()) return null

        val items = mutableListOf<String>()
        val params = mutableListOf<SqlBindParameterSet>()

        for (orderBy in orderBys) {
            val rendered = exprRenderer.renderSqlExpr(orderBy.expr)
            items.add(rendered.sql + (if (orderBy.desc) " DESC" else ""))
            params.add(rendered.bindParameterSet)
        }

        return SqlPlanRenderResult(
            sql = "ORDER BY " + items.joinToString(", "),
            bindParameterSet = Mergeable.mergeIterable(params, SqlBindParameterSet.EMPTY),
        )
    }

    /** Render the LIMIT clause, or null if [limitValue] is null. */
    protected open fun renderLimitSection(limitValue: Int?): SqlPlanRenderResult? {
        if (limitValue == null) return null
        return SqlPlanRenderResult(
            sql = "LIMIT $limitValue",
            bindParameterSet = SqlBindParameterSet.EMPTY,
        )
    }

    // ---------- Visitor methods ----------

    override fun visitCteNode(node: SqlCteNode): SqlPlanRenderResult {
        val lines = mutableListOf<String>()
        val collectedParams = mutableListOf<SqlBindParameterSet>()
        lines.add("${node.cteAlias} AS (")
        val selectRendered = node.selectStatement.accept(this)
        lines.add(mfIndent(selectRendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT))
        collectedParams.add(selectRendered.bindParameterSet)
        lines.add(")")

        return SqlPlanRenderResult(
            sql = lines.joinToString("\n"),
            bindParameterSet = Mergeable.mergeIterable(collectedParams, SqlBindParameterSet.EMPTY),
        )
    }

    override fun visitSelectStatementNode(
        node: SqlSelectStatementNode,
    ): SqlPlanRenderResult {
        val renderResults = listOfNotNull(
            renderDescriptionSection(node.description),
            renderCteSections(node.cteSources),
            renderSelectColumnsSection(node.selectColumns, node.parentNodes.size, node.distinct),
            renderFromSection(node.fromSource, node.fromSourceAlias),
            renderJoinsSection(node.joinDescs),
            renderWhere(node.where),
            renderGroupBySection(node.groupBys),
            renderOrderBySection(node.orderBys),
            renderLimitSection(node.limit),
        )

        return SqlPlanRenderResult(
            sql = renderResults.joinToString("\n") { it.sql },
            bindParameterSet = Mergeable.mergeIterable(
                renderResults.map { it.bindParameterSet },
                SqlBindParameterSet.EMPTY,
            ),
        )
    }

    override fun visitTableNode(node: SqlTableNode): SqlPlanRenderResult =
        SqlPlanRenderResult(sql = node.sqlTable.sql, bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitQueryFromClauseNode(node: SqlSelectTextNode): SqlPlanRenderResult =
        SqlPlanRenderResult(sql = node.selectQuery.trimEnd(), bindParameterSet = SqlBindParameterSet.EMPTY)

    override fun visitCreateTableAsNode(node: SqlCreateTableAsNode): SqlPlanRenderResult {
        val innerRendered = node.parentNode.accept(this)
        val indented = mfIndent(innerRendered.sql, indentLevel = 1, indentPrefix = SqlRenderingConstants.INDENT)
        val tableTypeKeyword = node.sqlTable.tableType.name.uppercase()
        // Matches Python's `CREATE <TYPE> <table> AS (\n  <inner>\n)` template.
        val sql = "CREATE $tableTypeKeyword ${node.sqlTable.sql} AS (\n$indented\n)"

        return SqlPlanRenderResult(sql = sql, bindParameterSet = innerRendered.bindParameterSet)
    }
}
