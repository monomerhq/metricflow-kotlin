package cc.monomer.metricflow.domain.sql.optimizer.column_pruning

import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode

/**
 * Mutable mapping from each [SqlPlanNode] to the set of column aliases tagged as required.
 *
 * Port of
 * `metricflow.sql.optimizer.column_pruning.node_to_column_alias_maping.NodeToColumnAliasMapping`.
 * A thin wrapper over a `Map<SqlPlanNode, MutableSet<String>>` for readability.
 *
 * The keys compare by reference equality (SQL plan nodes use identity equality from their
 * `DagNode` base) — the mapping is "the columns required at *this specific node in the
 * DAG*", not "the columns required at any structurally equal node".
 */
class NodeToColumnAliasMapping {

    private val nodeToTaggedAliases: MutableMap<SqlPlanNode, MutableSet<String>> = mutableMapOf()

    /** Return the column aliases tagged for [node], or the empty set if untouched. */
    fun getAliases(node: SqlPlanNode): Set<String> =
        nodeToTaggedAliases[node]?.toSet() ?: emptySet()

    /** Tag [columnAlias] as required for [node]. */
    fun addAlias(node: SqlPlanNode, columnAlias: String) {
        nodeToTaggedAliases.getOrPut(node) { mutableSetOf() }.add(columnAlias)
    }

    /** Tag every alias in [columnAliases] as required for [node]. */
    fun addAliases(node: SqlPlanNode, columnAliases: Iterable<String>) {
        nodeToTaggedAliases.getOrPut(node) { mutableSetOf() }.addAll(columnAliases)
    }
}
