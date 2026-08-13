package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode

/**
 * Apply a chain of [SqlPlanOptimizer] passes in order.
 *
 * Not a port of any Python class — Python's `MetricFlowEngine` runs the optimizer list
 * inline via `SqlGenerationOptionSet.optimizers`. We pull the loop out into a tiny
 * pipeline class so callers can chain passes (e.g. from a custom optimizer list, not just
 * the canonical [SqlGenerationOptionSet]) without restating the loop.
 */
class SqlPlanOptimizerPipeline(private val passes: List<SqlPlanOptimizer>) : SqlPlanOptimizer {

    override fun optimize(node: SqlPlanNode): SqlPlanNode {
        var current = node
        for (pass in passes) {
            current = pass.optimize(current)
        }
        return current
    }

    companion object {
        /**
         * The canonical pipeline for the given [SqlOptimizationLevel].
         *
         * Equivalent to chaining [SqlGenerationOptionSet.optionsForLevel].optimizers.
         */
        fun forLevel(
            level: SqlOptimizationLevel,
            useColumnAliasInGroupBy: Boolean,
        ): SqlPlanOptimizerPipeline =
            SqlPlanOptimizerPipeline(
                passes = SqlGenerationOptionSet.optionsForLevel(level, useColumnAliasInGroupBy).optimizers,
            )
    }
}
