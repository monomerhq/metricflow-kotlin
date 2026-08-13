package cc.monomer.metricflow.domain.sql.optimizer.column_pruning

import cc.monomer.metricflow.domain.sql.plan.SqlCteAliasMapping
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNodeVisitor
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Walks the plan once and produces a [SqlCteAliasMappingLookup] recording, for each
 * SELECT, the CTE aliases visible at that point (with inner CTEs shadowing outer ones).
 *
 * Port of
 * `metricflow.sql.optimizer.column_pruning.cte_mapping_lookup_builder.SqlCteAliasMappingLookupBuilderVisitor`.
 */
class SqlCteAliasMappingLookupBuilderVisitor : SqlPlanNodeVisitor<Unit> {

    private var currentCteAliasMapping: SqlCteAliasMapping = SqlCteAliasMapping.EMPTY
    private val internalLookup: SqlCteAliasMappingLookup = SqlCteAliasMappingLookup()

    /** Returns the lookup created after traversal. */
    val cteAliasMappingLookup: SqlCteAliasMappingLookup get() = internalLookup

    private inline fun saveCurrentMapping(block: () -> Unit) {
        val previous = currentCteAliasMapping
        try {
            block()
        } finally {
            currentCteAliasMapping = previous
        }
    }

    private fun defaultHandler(node: SqlPlanNode) {
        for (parentNode in node.parentNodes) {
            saveCurrentMapping { parentNode.accept(this) }
        }
    }

    override fun visitCteNode(node: SqlCteNode) {
        defaultHandler(node)
    }

    override fun visitSelectStatementNode(node: SqlSelectStatementNode) {
        if (internalLookup.cteAliasMappingExists(node)) {
            defaultHandler(node)
            return
        }

        // Inner CTEs shadow outer CTEs on alias collision.
        currentCteAliasMapping = currentCteAliasMapping.merge(
            SqlCteAliasMapping.create(node.cteSources.associateBy { it.cteAlias }),
        )
        internalLookup.addCteAliasMapping(
            selectNode = node,
            cteAliasMapping = currentCteAliasMapping,
        )
        defaultHandler(node)
    }

    override fun visitTableNode(node: SqlTableNode) {
        defaultHandler(node)
    }

    override fun visitQueryFromClauseNode(node: SqlSelectTextNode) {
        defaultHandler(node)
    }

    override fun visitCreateTableAsNode(node: SqlCreateTableAsNode) {
        defaultHandler(node)
    }
}
