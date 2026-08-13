package cc.monomer.metricflow.domain.sql.optimizer.column_pruning

import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode

/**
 * Records the CTE-alias mapping that is visible at a given [SqlSelectStatementNode].
 *
 * Port of
 * `metricflow.sql.optimizer.column_pruning.cte_alias_to_cte_node_mapping.SqlCteAliasMappingLookup`.
 *
 * In a SQL plan with nested CTEs, an inner CTE can shadow an outer CTE that shares its
 * alias. This lookup is built once via [SqlCteAliasMappingLookupBuilderVisitor] then read
 * by [SqlMapRequiredColumnAliasesVisitor] so that "which CTE does this table reference
 * actually point at?" is unambiguous.
 */
class SqlCteAliasMappingLookup {

    private val selectNodeToCteAliasMapping: MutableMap<SqlSelectStatementNode, SqlCteAliasMapping> = mutableMapOf()

    /** Returns true if a CTE-alias mapping for [selectNode] has been recorded. */
    fun cteAliasMappingExists(selectNode: SqlSelectStatementNode): Boolean =
        selectNodeToCteAliasMapping.containsKey(selectNode)

    /**
     * Associate the given CTE-alias mapping with [selectNode].
     *
     * Throws [IllegalStateException] if a mapping was already recorded for the node.
     */
    fun addCteAliasMapping(
        selectNode: SqlSelectStatementNode,
        cteAliasMapping: SqlCteAliasMapping,
    ) {
        check(!selectNodeToCteAliasMapping.containsKey(selectNode)) {
            "`selectNode` has already been added: $selectNode"
        }
        selectNodeToCteAliasMapping[selectNode] = cteAliasMapping
    }

    /** Return the CTE-alias mapping for [selectNode]; throws if none was previously added. */
    fun getCteAliasMapping(selectNode: SqlSelectStatementNode): SqlCteAliasMapping =
        selectNodeToCteAliasMapping[selectNode]
            ?: throw IllegalStateException(
                "CTE alias mapping does not exist for the given `selectNode`: $selectNode",
            )
}
