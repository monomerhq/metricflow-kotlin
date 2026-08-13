package cc.monomer.metricflow.domain.dataflow.optimizer

import cc.monomer.metricflow.domain.dataflow.optimizer.source_scan.SourceScanOptimizer

/**
 * Enumeration of optimizer passes available to the dataflow plan builder.
 *
 * Port of
 * `metricflow.dataflow.optimizer.dataflow_optimizer_factory.DataflowPlanOptimization`.
 *
 * Values indicate the order of application. `PASSTHROUGH_METRIC_EVALUATION` is handled at a
 * different stage of plan generation (it isn't a [DataflowPlanOptimizer] but a hint to the
 * metric-evaluation planner); listing it here matches Python so the factory enumeration is
 * consistent.
 */
enum class DataflowPlanOptimization(val order: Int) {
    SOURCE_SCAN(0),
    PASSTHROUGH_METRIC_EVALUATION(1),
    ;

    companion object {
        /** All optimizations as a set. Port of `all_optimizations`. */
        fun allOptimizations(): Set<DataflowPlanOptimization> = setOf(
            PASSTHROUGH_METRIC_EVALUATION,
            SOURCE_SCAN,
        )

        /**
         * Set of optimizations enabled by default. Port of `enabled_optimizations`.
         *
         * Note: the Python predicate-pushdown optimizer is currently disabled; only `SOURCE_SCAN`
         * is enabled. We mirror this default.
         */
        fun enabledOptimizations(): Set<DataflowPlanOptimization> = setOf(SOURCE_SCAN)
    }
}

/**
 * Builds an ordered sequence of optimizer instances from a set of requested optimizations.
 *
 * Port of
 * `metricflow.dataflow.optimizer.dataflow_optimizer_factory.DataflowPlanOptimizerFactory`.
 *
 * In Python the factory takes a `DataflowNodeToSqlSubqueryVisitor` so optimizers can share cached
 * data-set lookups. Until W9c lands that visitor, we accept `Unit` as a placeholder. Concrete
 * passes that need it (e.g. the future predicate-pushdown optimizer) will require constructor
 * changes when wired.
 */
class DataflowPlanOptimizerFactory {

    /**
     * Initialise and return optimizers matching the requested set, sorted by
     * [DataflowPlanOptimization.order]. Port of `get_optimizers`.
     *
     * `PASSTHROUGH_METRIC_EVALUATION` is silently skipped (handled elsewhere).
     */
    fun getOptimizers(
        optimizations: Set<DataflowPlanOptimization>,
    ): List<DataflowPlanOptimizer> {
        val result = mutableListOf<DataflowPlanOptimizer>()
        for (opt in optimizations.sortedBy { it.order }) {
            when (opt) {
                DataflowPlanOptimization.PASSTHROUGH_METRIC_EVALUATION -> {
                    // Handled through a separate step in plan generation.
                }
                DataflowPlanOptimization.SOURCE_SCAN -> result.add(SourceScanOptimizer())
            }
        }
        return result
    }
}
