package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.optimizer.column_pruning.SqlColumnPrunerOptimizer
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlOptimizationLevelTest {

    @Test
    fun `default level is O5`() {
        assertEquals(SqlOptimizationLevel.O5, SqlOptimizationLevel.DEFAULT_LEVEL)
    }

    @Test
    fun `O0 has no optimizers and no CTE`() {
        val options = SqlGenerationOptionSet.optionsForLevel(
            level = SqlOptimizationLevel.O0,
            useColumnAliasInGroupBy = false,
        )
        assertTrue(options.optimizers.isEmpty())
        assertFalse(options.allowCte)
    }

    @Test
    fun `O1 enables table-alias simplification only`() {
        val options = SqlGenerationOptionSet.optionsForLevel(
            level = SqlOptimizationLevel.O1,
            useColumnAliasInGroupBy = false,
        )
        assertEquals(1, options.optimizers.size)
        assertTrue(options.optimizers[0] is SqlTableAliasSimplifier)
        assertFalse(options.allowCte)
    }

    @Test
    fun `O2 enables column pruning + table-alias simplification`() {
        val options = SqlGenerationOptionSet.optionsForLevel(
            level = SqlOptimizationLevel.O2,
            useColumnAliasInGroupBy = false,
        )
        assertEquals(2, options.optimizers.size)
        assertTrue(options.optimizers[0] is SqlColumnPrunerOptimizer)
        assertTrue(options.optimizers[1] is SqlTableAliasSimplifier)
        assertFalse(options.allowCte)
    }

    @Test
    fun `O5 enables all passes and allowCte`() {
        val options = SqlGenerationOptionSet.optionsForLevel(
            level = SqlOptimizationLevel.O5,
            useColumnAliasInGroupBy = false,
        )
        assertEquals(3, options.optimizers.size)
        assertTrue(options.optimizers[0] is SqlColumnPrunerOptimizer)
        assertTrue(options.optimizers[1] is SqlRewritingSubQueryReducer)
        assertTrue(options.optimizers[2] is SqlTableAliasSimplifier)
        assertTrue(options.allowCte)
    }

    @Test
    fun `pipeline applies optimizers in order`() {
        val pipeline = SqlPlanOptimizerPipeline.forLevel(
            level = SqlOptimizationLevel.O2,
            useColumnAliasInGroupBy = false,
        )
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

        val optimized = pipeline.optimize(outer) as SqlSelectStatementNode
        val prunedInner = optimized.fromSource as SqlSelectStatementNode
        // O2 prunes inner unused columns first, then drops table aliases.
        assertEquals(1, prunedInner.selectColumns.size)
    }
}
