package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.optimizer.column_pruning.SqlColumnPrunerOptimizer
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlColumnPrunerOptimizerTest {

    private val optimizer = SqlColumnPrunerOptimizer()

    @Test
    fun `prunes unused inner columns from a sub-query`() {
        // Inner: SELECT events.id, events.unused FROM ana.events events
        // Outer: SELECT inner_alias.id FROM (inner) inner_alias
        // Optimizer drops `unused` from the inner select.
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(
                TestPlanFixtures.selectCol("events", "id"),
                TestPlanFixtures.selectCol("events", "unused"),
            ),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )

        val optimized = optimizer.optimize(outer) as SqlSelectStatementNode
        assertEquals(1, optimized.selectColumns.size)
        assertEquals("id", optimized.selectColumns[0].columnAlias)

        val prunedInner = optimized.fromSource as SqlSelectStatementNode
        assertEquals(1, prunedInner.selectColumns.size, "Inner select should be pruned to one column")
        assertEquals("id", prunedInner.selectColumns[0].columnAlias)
    }

    @Test
    fun `retains all columns when distinct`() {
        // A distinct select must keep all of its columns or the result-set semantics change.
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(
                TestPlanFixtures.selectCol("events", "id"),
                TestPlanFixtures.selectCol("events", "kept_distinct"),
            ),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
            distinct = true,
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )

        val optimized = optimizer.optimize(outer) as SqlSelectStatementNode
        val prunedInner = optimized.fromSource as SqlSelectStatementNode
        assertEquals(2, prunedInner.selectColumns.size, "DISTINCT keeps every column")
    }

    @Test
    fun `retains group by columns even when not used downstream`() {
        val inner = TestPlanFixtures.simpleSelect(
            description = "inner",
            selectColumns = listOf(
                TestPlanFixtures.selectCol("events", "id"),
                TestPlanFixtures.selectCol("events", "group_col"),
            ),
            fromTable = TestPlanFixtures.tableNode("ana", "events"),
            fromAlias = "events",
            groupBys = listOf(TestPlanFixtures.selectCol("events", "group_col")),
        )
        val outer = TestPlanFixtures.selectFromSubquery(
            outerSelectColumns = listOf(TestPlanFixtures.selectCol("inner_alias", "id")),
            innerSelect = inner,
            innerAlias = "inner_alias",
        )

        val optimized = optimizer.optimize(outer) as SqlSelectStatementNode
        val prunedInner = optimized.fromSource as SqlSelectStatementNode
        // group_col must remain because it's in the GROUP BY (dropping it would change semantics).
        assertEquals(
            setOf("id", "group_col"),
            prunedInner.selectColumns.mapTo(mutableSetOf()) { it.columnAlias },
        )
    }
}
