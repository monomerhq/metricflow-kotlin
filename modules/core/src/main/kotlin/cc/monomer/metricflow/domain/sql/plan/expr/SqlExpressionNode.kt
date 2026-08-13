package cc.monomer.metricflow.domain.sql.plan.expr

import cc.monomer.metricflow.common.dag.DagNode
import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.spec.bind.SqlBindParameterSet

/**
 * A node in the SQL expression tree — e.g. `my_table.my_column`, `CONCAT(a, b)`, `1 + 1`.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlExpressionNode`. The Python class is an
 * abstract `DagNode` subclass with ~20 concrete variants; we restate it as an `abstract
 * class` rooted at this type. The full set of variants is enumerated by
 * [SqlExpressionNodeVisitor] (adding a new variant requires extending the visitor — same
 * discipline as Python).
 *
 * We don't seal this class. Kotlin 2 requires direct subclasses of a sealed class to live
 * in the same package, but we want the variants organised under `expr/`, and we want
 * [SqlFunctionExpression] to be an intermediate abstract class whose own subclasses
 * ([SqlAggregateFunctionExpression], [SqlPercentileExpression], [SqlWindowFunctionExpression])
 * extend it directly. Reaching for the visitor's exhaustive dispatch (via the
 * [SqlExpressionNodeVisitor] interface) gives us the same closed-set guarantee.
 */
abstract class SqlExpressionNode(parentNodes: List<SqlExpressionNode>) :
    DagNode<SqlExpressionNode>(parentNodes) {

    /**
     * Whether this expression needs surrounding `(...)` when rendered as a child of another.
     *
     * Port of `SqlExpressionNode.requires_parenthesis`. Always defined per-variant.
     */
    abstract val requiresParenthesis: Boolean

    /**
     * Execution parameters needed to run a query containing this expression. Most variants
     * return [SqlBindParameterSet.EMPTY]; string-template expressions ([SqlStringExpression])
     * are the main exception.
     */
    open val bindParameterSet: SqlBindParameterSet
        get() = SqlBindParameterSet.EMPTY

    /** Convenience downcast: returns `this` iff this is a [SqlColumnReferenceExpression]. */
    open val asColumnReferenceExpression: SqlColumnReferenceExpression? get() = null

    /** Convenience downcast: returns `this` iff this is a [SqlColumnAliasReferenceExpression]. */
    open val asColumnAliasReferenceExpression: SqlColumnAliasReferenceExpression? get() = null

    /** Convenience downcast: returns `this` iff this is a [SqlStringExpression]. */
    open val asStringExpression: SqlStringExpression? get() = null

    /** Convenience downcast: returns `this` iff this is a [SqlWindowFunctionExpression]. */
    open val asWindowFunctionExpression: SqlWindowFunctionExpression? get() = null

    /**
     * Whether this expression tends to render long and is therefore hard to read when
     * collapsed by the optimizer. Window functions and CASE WHEN trees set this to true.
     */
    open val isVerbose: Boolean get() = false

    /** Visitor dispatch — port of `SqlExpressionNode.accept`. */
    abstract fun <R> accept(visitor: SqlExpressionNodeVisitor<R>): R

    /** Returns all ancestor expression nodes including self. Port of `SqlExpressionNode.lineage`. */
    abstract val lineage: SqlExpressionTreeLineage

    /**
     * Return an equivalent expression with column references re-bound and table-alias rendering
     * possibly toggled. Port of `SqlExpressionNode.rewrite`. Both arguments are nullable:
     * `null` means "no change for this concern".
     */
    abstract fun rewrite(
        columnReplacements: SqlColumnReplacements?,
        shouldRenderTableAlias: Boolean?,
    ): SqlExpressionNode

    /** Semantic equality with another node — Python's `SqlExpressionNode.matches`. */
    abstract fun matches(other: SqlExpressionNode): Boolean

    /** Whether the corresponding [parentNodes] in [other] all match. */
    protected fun parentsMatch(other: SqlExpressionNode): Boolean {
        if (parentNodes.size != other.parentNodes.size) return false
        return parentNodes.zip(other.parentNodes).all { (a, b) -> a == b }
    }
}

/**
 * Lineage information for an expression tree — every ancestor node, partitioned by kind.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlExpressionTreeLineage`.
 */
data class SqlExpressionTreeLineage(
    val stringExprs: List<SqlStringExpression> = emptyList(),
    val functionExprs: List<SqlFunctionExpression> = emptyList(),
    val columnReferenceExprs: List<SqlColumnReferenceExpression> = emptyList(),
    val columnAliasReferenceExprs: List<SqlColumnAliasReferenceExpression> = emptyList(),
    val otherExprs: List<SqlExpressionNode> = emptyList(),
) : Mergeable<SqlExpressionTreeLineage> {

    val containsStringExprs: Boolean get() = stringExprs.isNotEmpty()
    val containsColumnAliasExprs: Boolean get() = columnAliasReferenceExprs.isNotEmpty()
    val containsAmbiguousExprs: Boolean get() = containsStringExprs || containsColumnAliasExprs
    val containsAggregateExprs: Boolean get() = functionExprs.any { it.isAggregateFunction }

    override fun merge(other: SqlExpressionTreeLineage): SqlExpressionTreeLineage =
        SqlExpressionTreeLineage(
            stringExprs = stringExprs + other.stringExprs,
            functionExprs = functionExprs + other.functionExprs,
            columnReferenceExprs = columnReferenceExprs + other.columnReferenceExprs,
            columnAliasReferenceExprs = columnAliasReferenceExprs + other.columnAliasReferenceExprs,
            otherExprs = otherExprs + other.otherExprs,
        )

    companion object {
        val EMPTY: SqlExpressionTreeLineage = SqlExpressionTreeLineage()

        /** Merge a sequence of lineages — same shape as Python `merge_iterable`. */
        fun mergeIterable(items: Iterable<SqlExpressionTreeLineage>): SqlExpressionTreeLineage =
            Mergeable.mergeIterable(items, EMPTY)
    }
}

/**
 * Maps column references to replacement expressions for [SqlExpressionNode.rewrite].
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlColumnReplacements`.
 */
class SqlColumnReplacements(private val replacements: Map<SqlColumnReference, SqlExpressionNode>) {
    fun getReplacement(columnReference: SqlColumnReference): SqlExpressionNode? =
        replacements[columnReference]
}

/**
 * Visitor over the [SqlExpressionNode] sealed hierarchy.
 *
 * Port of `metricflow_semantics.sql.sql_exprs.SqlExpressionNodeVisitor`. Adding a new
 * expression variant requires adding a method here.
 */
interface SqlExpressionNodeVisitor<R> {
    fun visitStringExpr(node: SqlStringExpression): R
    fun visitStringLiteralExpr(node: SqlStringLiteralExpression): R
    fun visitIntegerExpr(node: SqlIntegerExpression): R
    fun visitColumnReferenceExpr(node: SqlColumnReferenceExpression): R
    fun visitColumnAliasReferenceExpr(node: SqlColumnAliasReferenceExpression): R
    fun visitComparisonExpr(node: SqlComparisonExpression): R
    fun visitFunctionExpr(node: SqlAggregateFunctionExpression): R
    fun visitPercentileExpr(node: SqlPercentileExpression): R
    fun visitNullExpr(node: SqlNullExpression): R
    fun visitLogicalExpr(node: SqlLogicalExpression): R
    fun visitIsNullExpr(node: SqlIsNullExpression): R
    fun visitCastToTimestampExpr(node: SqlCastToTimestampExpression): R
    fun visitDateTruncExpr(node: SqlDateTruncExpression): R
    fun visitExtractExpr(node: SqlExtractExpression): R
    fun visitSubtractTimeIntervalExpr(node: SqlSubtractTimeIntervalExpression): R
    fun visitAddTimeExpr(node: SqlAddTimeExpression): R
    fun visitRatioComputationExpr(node: SqlRatioComputationExpression): R
    fun visitBetweenExpr(node: SqlBetweenExpression): R
    fun visitWindowFunctionExpr(node: SqlWindowFunctionExpression): R
    fun visitGenerateUuidExpr(node: SqlGenerateUuidExpression): R
    fun visitCaseExpr(node: SqlCaseExpression): R
    fun visitArithmeticExpr(node: SqlArithmeticExpression): R
}
