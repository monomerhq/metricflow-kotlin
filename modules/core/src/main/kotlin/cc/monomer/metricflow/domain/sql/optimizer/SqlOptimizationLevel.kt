package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.optimizer.column_pruning.SqlColumnPrunerOptimizer

/**
 * Optimization tiers — higher values apply more aggressive rewrites.
 *
 * Port of `metricflow.sql.optimizer.optimization_levels.SqlOptimizationLevel`. Comparison
 * is by name (so `O0 < O1 < ... < O5`).
 */
enum class SqlOptimizationLevel {
    O0,
    O1,
    O2,
    O3,
    O4,
    O5;

    companion object {
        /** Default optimization level (matches Python's `SqlOptimizationLevel.default_level()`). */
        val DEFAULT_LEVEL: SqlOptimizationLevel = O5
    }
}

/**
 * The SQL-generation options associated with a [SqlOptimizationLevel] — the optimizer
 * passes to apply and the `allowCte` flag for the dataflow-to-SQL converter.
 *
 * Port of `metricflow.sql.optimizer.optimization_levels.SqlGenerationOptionSet`. The
 * association is computed by [optionsForLevel] (Python's `options_for_level`).
 */
data class SqlGenerationOptionSet(
    val optimizers: List<SqlPlanOptimizer>,
    /** Whether CTEs may be used to simplify generated SQL. */
    val allowCte: Boolean,
) {
    companion object {
        /**
         * Build the option set for [level]. The [useColumnAliasInGroupBy] flag is forwarded
         * to [SqlRewritingSubQueryReducer] so engines (e.g. Trino) that prefer
         * alias-based GROUP BY can opt in.
         */
        fun optionsForLevel(
            level: SqlOptimizationLevel,
            useColumnAliasInGroupBy: Boolean,
        ): SqlGenerationOptionSet {
            val optimizers: List<SqlPlanOptimizer>
            var allowCte = false

            when (level) {
                SqlOptimizationLevel.O0 -> {
                    optimizers = emptyList()
                }
                SqlOptimizationLevel.O1 -> {
                    optimizers = listOf(SqlTableAliasSimplifier())
                }
                SqlOptimizationLevel.O2,
                SqlOptimizationLevel.O3 -> {
                    optimizers = listOf(SqlColumnPrunerOptimizer(), SqlTableAliasSimplifier())
                }
                SqlOptimizationLevel.O4 -> {
                    optimizers = listOf(
                        SqlColumnPrunerOptimizer(),
                        SqlRewritingSubQueryReducer(useColumnAliasInGroupBys = useColumnAliasInGroupBy),
                        SqlTableAliasSimplifier(),
                    )
                }
                SqlOptimizationLevel.O5 -> {
                    optimizers = listOf(
                        SqlColumnPrunerOptimizer(),
                        SqlRewritingSubQueryReducer(useColumnAliasInGroupBys = useColumnAliasInGroupBy),
                        SqlTableAliasSimplifier(),
                    )
                    allowCte = true
                }
            }

            return SqlGenerationOptionSet(
                optimizers = optimizers,
                allowCte = allowCte,
            )
        }
    }
}
