package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCreateTableAsNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode

/**
 * Collects physical relations from the same optimized plan that is sent to a renderer.
 *
 * A [SqlTableNode] whose unqualified name resolves to a lexical CTE is a reference to a
 * derived relation and is therefore omitted. Unqualified names that do not resolve to a CTE
 * are retained: a warehouse may legitimately use them as physical relations. The collector
 * deliberately refuses opaque query text and CTAS nodes because neither can provide a complete
 * relation proof without reparsing SQL.
 */
object SqlPlanRelationCollector {

    /**
     * Return every physical relation referenced by [plan], preserving first-seen order.
     *
     * This function walks plan nodes only. It never inspects rendered SQL.
     */
    fun collect(plan: SqlPlan): Set<SqlTable> = Collector().collect(plan.renderNode)

    private class Collector {
        fun collect(node: SqlPlanNode): Set<SqlTable> = collectNode(node, SqlCteAliasMapping.EMPTY)

        private fun collectNode(
            node: SqlPlanNode,
            visibleCtes: SqlCteAliasMapping,
        ): Set<SqlTable> = node.accept(ContextualVisitor(visibleCtes, this))

        private fun collectSelect(
            node: SqlSelectStatementNode,
            outerCtes: SqlCteAliasMapping,
        ): Set<SqlTable> {
            // A SELECT's local aliases shadow an outer alias in all nested plan branches.
            // SqlCteAliasMapping is the same lexical map used by column-pruning.
            val visibleCtes = outerCtes.merge(
                SqlCteAliasMapping.create(node.cteSources.associateBy { it.cteAlias }),
            )
            val relations = LinkedHashSet<SqlTable>()
            relations.addAll(collectNode(node.fromSource, visibleCtes))
            for (join in node.joinDescs) {
                relations.addAll(collectNode(join.rightSource, visibleCtes))
            }
            for (cte in node.cteSources) {
                relations.addAll(collectNode(cte.selectStatement, visibleCtes))
            }
            return relations
        }

        /**
         * The visitor is instantiated per branch so a relation node is evaluated with the
         * lexical CTE map carried by that branch. This keeps the visitor closed over the
         * existing node visitor hierarchy without introducing mutable global traversal state.
         */
        private class ContextualVisitor(
            private val visibleCtes: SqlCteAliasMapping,
            private val collector: Collector,
        ) : SqlPlanNodeVisitor<Set<SqlTable>> {
            override fun visitSelectStatementNode(node: SqlSelectStatementNode): Set<SqlTable> =
                collector.collectSelect(node, visibleCtes)

            override fun visitTableNode(node: SqlTableNode): Set<SqlTable> =
                if (node.sqlTable.schemaName == null &&
                    visibleCtes.getCteNodeForAlias(node.sqlTable.tableName) != null
                ) {
                    emptySet()
                } else {
                    linkedSetOf(node.sqlTable)
                }

            override fun visitQueryFromClauseNode(node: SqlSelectTextNode): Set<SqlTable> =
                throw SqlPlanRelationCollectionException(
                    "Cannot prove relations for opaque SqlSelectTextNode",
                )

            override fun visitCreateTableAsNode(node: SqlCreateTableAsNode): Set<SqlTable> =
                throw SqlPlanRelationCollectionException(
                    "Cannot prove relations for SqlCreateTableAsNode",
                )

            override fun visitCteNode(node: SqlCteNode): Set<SqlTable> =
                collector.collectNode(node.selectStatement, visibleCtes)
        }
    }
}

/** Raised when a SQL plan cannot yield a complete physical-relation proof. */
internal class SqlPlanRelationCollectionException(message: String) : RuntimeException(message)
