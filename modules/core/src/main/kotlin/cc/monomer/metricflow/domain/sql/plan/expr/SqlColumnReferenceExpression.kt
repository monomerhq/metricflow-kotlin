package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix

/**
 * Combined reference to a single column inside a particular table-alias-qualified source.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlColumnReference`. This is not itself an
 * expression node — it's the value field inside [SqlColumnReferenceExpression].
 */
data class SqlColumnReference(val tableAlias: String, val columnName: String)

/**
 * Reference to a column in a source — e.g. `src.my_column`. Either a single `column` or a
 * `table.column` pair, depending on [shouldRenderTableAlias].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlColumnReferenceExpression`. The Python
 * `rewrite` method has a special hack for the column name `"user"` (which is a reserved
 * SQL keyword in some engines) — we preserve that, including the comment.
 */
class SqlColumnReferenceExpression(
    val colRef: SqlColumnReference,
    val shouldRenderTableAlias: Boolean,
) : SqlExpressionNode(emptyList()) {

    override val description: String get() = "Column: $colRef"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.SQL_EXPR_COLUMN_REFERENCE_ID_PREFIX
    override val requiresParenthesis: Boolean get() = false

    override val displayedProperties: List<DisplayedProperty>
        get() = super.displayedProperties + DisplayedProperty("col_ref", colRef)

    override val asColumnReferenceExpression: SqlColumnReferenceExpression? get() = this

    override fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R =
        visitor.visitColumnReferenceExpr(this)

    override fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode {
        // TODO: Hack to work around the fact our test data set contains "user", which is a
        // reserved keyword. We should migrate "user" -> "user_id" in the test set.
        // This will force "user" to be rendered as "table_alias.user".
        val effectiveRenderAlias = if (colRef.columnName == "user") true else shouldRenderTableAlias

        if (columnReplacements != null) {
            val replacement = columnReplacements.getReplacement(colRef)
            if (replacement != null) {
                return if (effectiveRenderAlias != null) {
                    replacement.rewrite(null, effectiveRenderAlias)
                } else {
                    replacement
                }
            }
            if (effectiveRenderAlias != null) {
                return create(colRef = colRef, shouldRenderTableAlias = effectiveRenderAlias)
            }
            return this
        }

        if (effectiveRenderAlias != null) {
            return create(colRef = colRef, shouldRenderTableAlias = effectiveRenderAlias)
        }
        return create(colRef = colRef, shouldRenderTableAlias = this.shouldRenderTableAlias)
    }

    override val lineage: SqlExpressionTreeLineage
        get() = SqlExpressionTreeLineage(columnReferenceExprs = listOf(this))

    override fun matches(other: SqlExpressionNode): Boolean =
        other is SqlColumnReferenceExpression && colRef == other.colRef

    /** Return an equivalent reference under a new table alias. */
    fun withNewTableAlias(newTableAlias: String): SqlColumnReferenceExpression =
        fromColumnReference(tableAlias = newTableAlias, columnName = colRef.columnName)

    companion object {
        fun create(
            colRef: SqlColumnReference,
            shouldRenderTableAlias: Boolean,
        ): SqlColumnReferenceExpression = SqlColumnReferenceExpression(colRef, shouldRenderTableAlias)

        fun fromColumnReference(tableAlias: String, columnName: String): SqlColumnReferenceExpression =
            create(
                colRef = SqlColumnReference(tableAlias = tableAlias, columnName = columnName),
                shouldRenderTableAlias = true,
            )
    }
}
