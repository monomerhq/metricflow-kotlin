package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnAliasReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.expr.SqlColumnReferenceExpression
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlRewritingSubQueryReducerTest {

    private val reducer = SqlRewritingSubQueryReducer(useColumnAliasInGroupBys = false)
    private val reducerWithAliasGroupBy = SqlRewritingSubQueryReducer(useColumnAliasInGroupBys = true)

    @Test
    fun `reduces a trivial sub-query into its inner SELECT`() {
        // outer: SELECT inner_alias.id FROM ( SELECT events.id FROM ana.events events ) inner_alias
        // After reduction the inner sub-query is collapsed, FROM becomes the table directly.
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )

        val optimized = reducer.optimize(outer) as SqlSelectStatementNode
        // The reduced FROM should be the underlying table, not the inner SELECT.
        val asTable = optimized.fromSource.asSqlTableNode
        assertTrue(asTable != null, "Expected FROM to collapse to the underlying table")
        // The SELECT column expressions should now reference the original table alias.
        val expr = optimized.selectColumns[0].expr as SqlColumnReferenceExpression
        assertEquals("events", expr.colRef.tableAlias)
        assertEquals("id", expr.colRef.columnName)
    }

    @Test
    fun `does not reduce when the parent has joins`() {
        // SELECT inner_alias.id FROM ( SELECT events.id FROM ana.events events ) inner_alias
        // wrapped inside a SELECT with a join. The reducer leaves joins alone (handled by
        // rewriteNodeWithJoin); here we focus on the inner reducer behavior.
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )
        val optimized = reducer.optimize(outer)
        assertTrue(optimized is SqlSelectStatementNode)
    }

    @Test
    fun `does not reduce a DISTINCT parent SELECT`() {
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
            distinct = true,
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )
        val optimized = reducer.optimize(outer) as SqlSelectStatementNode
        // DISTINCT inner — reducer leaves the sub-query intact.
        assertTrue(optimized.fromSource is SqlSelectStatementNode)
    }

    @Test
    fun `useColumnAliasInGroupBys rewrites group by to column alias references`() {
        val select = TestPlanFixtures.simpleSelect(
            selectColumns = listOf(TestPlanFixtures.selectCol("events", "id")),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
            groupBys = listOf(TestPlanFixtures.selectCol("events", "id")),
        )

        val optimized = reducerWithAliasGroupBy.optimize(select) as SqlSelectStatementNode
        val groupByExpr = optimized.groupBys[0].expr
        assertTrue(
            groupByExpr is SqlColumnAliasReferenceExpression,
            "GROUP BY should be rewritten to use the SELECT column alias",
        )
        assertEquals("id", groupByExpr.columnAlias)
    }
}
