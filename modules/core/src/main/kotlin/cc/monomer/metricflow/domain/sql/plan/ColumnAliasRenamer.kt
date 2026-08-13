package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReference
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode

/**
 * Helper to rename column aliases in a SQL `SELECT` statement.
 *
 * Port of `metricflow.sql.column_alias_renamer.ColumnAliasRenamer`. The two operations:
 *
 * 1. [rename] — rewrites every select column's alias in place. Use when the rename can be
 *    expressed without breaking outer references (i.e. there's no enclosing WHERE clause
 *    that already names the old alias).
 * 2. [renameViaSubquery] — wraps the original SELECT inside an inner subquery and exposes
 *    the renamed columns in the outer query. Use when the rename has to be visible to a
 *    WHERE clause that some engines evaluate before the SELECT aliases.
 */
class ColumnAliasRenamer {

    /**
     * Rewrites every select column alias according to the supplied map.
     *
     * Every existing alias **must** appear as a key in [previousToNext]; otherwise the
     * map access throws (same behaviour as Python's `previous_column_alias_to_next_column_alias[...]`).
     */
    fun rename(
        selectStatementNode: SqlSelectStatementNode,
        previousToNext: Map<String, String>,
    ): SqlSelectStatementNode = selectStatementNode.withSelectColumns(
        selectStatementNode.selectColumns.map { selectColumn ->
            selectColumn.copyWithNewAlias(
                previousToNext[selectColumn.columnAlias]
                    ?: error("Missing rename entry for alias '${selectColumn.columnAlias}'"),
            )
        },
    )

    /**
     * Rewrites aliases via a wrapping subquery so the new names are visible to an outer
     * `WHERE` clause. Port of `ColumnAliasRenamer.rename_via_subquery`.
     */
    fun renameViaSubquery(
        selectStatementNode: SqlSelectStatementNode,
        previousToNext: Map<String, String>,
        description: String,
        innerQueryAlias: String,
    ): SqlSelectStatementNode {
        val outerColumns = previousToNext.values.map { nextName ->
            SqlSelectColumn(
                expr = SqlColumnReferenceExpression.create(
                    colRef = SqlColumnReference(tableAlias = innerQueryAlias, columnName = nextName),
                    shouldRenderTableAlias = true,
                ),
                columnAlias = nextName,
            )
        }
        val innerSelect = selectStatementNode.withSelectColumns(
            selectStatementNode.selectColumns.map { selectColumn ->
                selectColumn.copyWithNewAlias(
                    previousToNext[selectColumn.columnAlias]
                        ?: error("Missing rename entry for alias '${selectColumn.columnAlias}'"),
                )
            },
        )
        return SqlSelectStatementNode.create(
            description = description,
            selectColumns = outerColumns,
            fromSource = innerSelect,
            fromSourceAlias = innerQueryAlias,
            cteSources = emptyList(),
            joinDescs = emptyList(),
            groupBys = emptyList(),
            orderBys = emptyList(),
            where = null,
            limit = null,
            distinct = false,
        )
    }
}
