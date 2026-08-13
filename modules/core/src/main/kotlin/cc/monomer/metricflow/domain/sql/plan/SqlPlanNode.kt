package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.DagNode
import cc.monomer.metricflow.common.dag.MetricFlowDag
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * A node in the SQL plan DAG — the AST that the dataflow→SQL converter produces and the
 * dialect renderers consume.
 *
 * Port of `metricflow.sql.sql_plan.SqlPlanNode`. The Python class is an abstract DagNode
 * subclass with five concrete variants; we restate the hierarchy as an `abstract class`
 * rooted here, with variants under [nodes/]. The closed set is enumerated by
 * [SqlPlanNodeVisitor]:
 *
 * - [SqlSelectStatementNode] — a `SELECT ...` statement
 * - [SqlTableNode] — a literal table reference (`FROM <schema>.<table>`)
 * - [SqlSelectTextNode] — a raw SQL query string used as a subquery
 * - [SqlCreateTableAsNode] — `CREATE TABLE <x> AS <select>`
 * - [SqlCteNode] — a `WITH <alias> AS (<select>)` common table expression
 *
 * We don't seal this class for the same reason as [cc.monomer.metricflow.domain.sql.plan.expr.SqlExpressionNode]:
 * we want variants under a `nodes/` subpackage. The visitor pattern enforces the
 * closed-set discipline.
 */
abstract class SqlPlanNode(parentNodes: List<SqlPlanNode>) : DagNode<SqlPlanNode>(parentNodes) {

    /** Visitor dispatch — port of `SqlPlanNode.accept`. */
    abstract fun <R> accept(visitor: SqlPlanNodeVisitor<R>): R

    /** Convenience downcast: returns `this` iff this is a [SqlSelectStatementNode]. */
    abstract val asSelectNode: SqlSelectStatementNode?

    /** Convenience downcast: returns `this` iff this is a [SqlTableNode]. */
    abstract val asSqlTableNode: SqlTableNode?

    /**
     * Returns the SELECT columns at this node or the closest ancestor that has them.
     *
     * Port of `SqlPlanNode.nearest_select_columns`. Used by column-pruning to figure out
     * which columns a leaf needs.
     */
    abstract fun nearestSelectColumns(cteSourceMapping: SqlCteAliasMapping): List<SqlSelectColumn>?

    /** Return a copy of the branch rooted at this node; fields are copied by reference. */
    abstract fun copyNode(): SqlPlanNode
}

/**
 * Visitor over the [SqlPlanNode] sealed hierarchy.
 *
 * Port of `metricflow.sql.sql_plan.SqlPlanNodeVisitor`.
 */
interface SqlPlanNodeVisitor<R> {
    fun visitSelectStatementNode(node: SqlSelectStatementNode): R
    fun visitTableNode(node: SqlTableNode): R
    fun visitQueryFromClauseNode(node: SqlSelectTextNode): R
    fun visitCreateTableAsNode(node: SqlCreateTableAsNode): R
    fun visitCteNode(node: SqlCteNode): R
}

/**
 * A column in the SELECT clause of a [SqlSelectStatementNode] — the expression plus its
 * column alias.
 *
 * Port of `metricflow.sql.sql_plan.SqlSelectColumn`. The alias is always required in our
 * model (matching Python's "always require a column alias for simplicity" comment).
 */
data class SqlSelectColumn(val expr: SqlExpressionNode, val columnAlias: String) {

    /** Return a column-reference expression pointing at this aliased column from [sourceTableAlias]. */
    fun referenceFrom(sourceTableAlias: String): SqlColumnReferenceExpression =
        SqlColumnReferenceExpression.fromColumnReference(
            tableAlias = sourceTableAlias,
            columnName = columnAlias,
        )

    /** Return a copy with the alias replaced. */
    fun copyWithNewAlias(columnAlias: String): SqlSelectColumn =
        copy(columnAlias = columnAlias)

    companion object {
        /** Convenience factory for selecting a column by name from a table alias. */
        fun fromColumnReference(tableAlias: String, columnName: String): SqlSelectColumn =
            SqlSelectColumn(
                expr = SqlColumnReferenceExpression.fromColumnReference(
                    tableAlias = tableAlias,
                    columnName = columnName,
                ),
                columnAlias = columnName,
            )
    }
}

/**
 * Lookup from CTE alias → [SqlCteNode]. Used during `nearestSelectColumns` traversal so
 * that a [SqlTableNode] pointing at a CTE can resolve back to the CTE's SELECT columns.
 *
 * Port of `metricflow.sql.sql_cte_node.SqlCteAliasMapping`.
 */
data class SqlCteAliasMapping(
    val cteAliasToCteNodeItems: List<Pair<String, SqlCteNode>> = emptyList(),
) : Mergeable<SqlCteAliasMapping> {

    private val asMap: Map<String, SqlCteNode> by lazy {
        LinkedHashMap<String, SqlCteNode>().apply {
            for ((alias, node) in cteAliasToCteNodeItems) put(alias, node)
        }
    }

    /** Return the associated [SqlCteNode] for the given alias, or `null` if not known. */
    fun getCteNodeForAlias(cteAlias: String): SqlCteNode? = asMap[cteAlias]

    /** Merge two mappings — entries in [other] take precedence on key collision. */
    override fun merge(other: SqlCteAliasMapping): SqlCteAliasMapping {
        val merged = LinkedHashMap(asMap)
        for ((alias, node) in other.cteAliasToCteNodeItems) {
            merged[alias] = node
        }
        return create(merged)
    }

    companion object {
        val EMPTY: SqlCteAliasMapping = SqlCteAliasMapping()

        /** Convenience factory: build a mapping from any [Map] (preserves iteration order). */
        fun create(cteAliasToCteNodeMapping: Map<String, SqlCteNode>): SqlCteAliasMapping =
            SqlCteAliasMapping(cteAliasToCteNodeMapping.map { (k, v) -> k to v })
    }
}

/**
 * The top-level SQL plan — a DAG with a single render-node sink, used by the renderer
 * to find the starting node.
 *
 * Port of `metricflow.sql.sql_plan.SqlPlan`.
 */
class SqlPlan(
    val renderNode: SqlPlanNode,
    planId: DagId,
) : MetricFlowDag<SqlPlanNode>(
    dagId = planId,
    sinkNodes = listOf(renderNode),
) {
    /** Construct a plan with an auto-generated DagId. */
    constructor(renderNode: SqlPlanNode) : this(
        renderNode = renderNode,
        planId = DagId.fromIdPrefix(StaticIdPrefix.SQL_PLAN_PREFIX),
    )
}
