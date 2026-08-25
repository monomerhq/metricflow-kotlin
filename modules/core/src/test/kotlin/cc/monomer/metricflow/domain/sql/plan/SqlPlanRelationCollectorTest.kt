package cc.monomer.metricflow.domain.sql.plan

import cc.monomer.metricflow.domain.spec.bind.SqlTable
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlCteNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectTextNode
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlTableNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlPlanRelationCollectorTest {

    @Test
    fun `collects physical relations and preserves unqualified tables`() {
        val qualified = SqlTable(schemaName = "analytics", tableName = "orders")
        val unqualified = SqlTable(schemaName = null, tableName = "events")
        val plan = SqlPlan(
            select(
                fromSource = SqlTableNode.create(qualified),
                joins = listOf(SqlTableNode.create(unqualified)),
            ),
        )

        assertEquals(
            linkedSetOf(qualified, unqualified),
            SqlPlanRelationCollector.collect(plan),
        )
    }

    @Test
    fun `omits a table node that resolves to a lexical CTE`() {
        val base = SqlTable(schemaName = "analytics", tableName = "orders")
        val cte = SqlCteNode.create(
            selectStatement = select(SqlTableNode.create(base)),
            cteAlias = "orders_cte",
        )
        val outer = select(
            fromSource = SqlTableNode.create(
                SqlTable(schemaName = null, tableName = "orders_cte"),
            ),
            ctes = listOf(cte),
        )

        assertEquals(linkedSetOf(base), SqlPlanRelationCollector.collect(SqlPlan(outer)))
    }

    @Test
    fun `local CTE aliases shadow outer aliases`() {
        val outerBase = SqlTable(schemaName = "analytics", tableName = "outer_orders")
        val innerBase = SqlTable(schemaName = "analytics", tableName = "inner_orders")
        val outerCte = SqlCteNode.create(select(SqlTableNode.create(outerBase)), "shared")
        val innerCte = SqlCteNode.create(select(SqlTableNode.create(innerBase)), "shared")
        val innerSelect = select(
            fromSource = SqlTableNode.create(SqlTable(null, "shared")),
            ctes = listOf(innerCte),
        )
        val outerSelect = select(
            fromSource = SqlTableNode.create(SqlTable(null, "shared")),
            ctes = listOf(outerCte, SqlCteNode.create(innerSelect, "inner_query")),
        )

        assertEquals(
            linkedSetOf(outerBase, innerBase),
            SqlPlanRelationCollector.collect(SqlPlan(outerSelect)),
        )
    }

    @Test
    fun `fails closed for opaque query text`() {
        val plan = SqlPlan(
            select(
                fromSource = SqlSelectTextNode.create("SELECT 1 FROM analytics.orders"),
            ),
        )

        assertFailsWith<SqlPlanRelationCollectionException> {
            SqlPlanRelationCollector.collect(plan)
        }
    }

    private fun select(
        fromSource: cc.monomer.metricflow.domain.sql.plan.SqlPlanNode,
        joins: List<cc.monomer.metricflow.domain.sql.plan.SqlPlanNode> = emptyList(),
        ctes: List<SqlCteNode> = emptyList(),
    ): SqlSelectStatementNode = SqlSelectStatementNode.create(
        description = "",
        selectColumns = emptyList(),
        fromSource = fromSource,
        fromSourceAlias = "source",
        cteSources = ctes,
        joinDescs = joins.map { source ->
            cc.monomer.metricflow.domain.sql.plan.nodes.SqlJoinDescription(
                rightSource = source,
                rightSourceAlias = "joined",
                joinType = cc.monomer.metricflow.domain.spec.bind.SqlJoinType.INNER,
                onCondition = null,
            )
        },
        groupBys = emptyList(),
        orderBys = emptyList(),
        where = null,
        limit = null,
        distinct = false,
    )
}
