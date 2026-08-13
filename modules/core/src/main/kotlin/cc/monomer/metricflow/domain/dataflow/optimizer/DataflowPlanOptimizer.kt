package cc.monomer.metricflow.domain.dataflow.optimizer

import cc.monomer.metricflow.domain.dataflow.DataflowPlan

/**
 * Converts one [DataflowPlan] into another that is more optimal in some way (e.g. performance).
 *
 * Port of `metricflow.dataflow.optimizer.dataflow_plan_optimizer.DataflowPlanOptimizer`. Each
 * pass is independent and re-rooted at the input plan's sink; they compose by chaining.
 *
 * Concrete passes implemented in this module:
 *
 * - [SourceScanOptimizer] — combines sibling `ComputeMetricsNode` branches feeding into a
 *   `CombineAggregatedOutputsNode` whenever their underlying scans can be unified, reducing the
 *   number of [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode]s in the
 *   final SQL.
 */
fun interface DataflowPlanOptimizer {
    /** Apply this optimization pass to [dataflowPlan] and return the rewritten plan. */
    fun optimize(dataflowPlan: DataflowPlan): DataflowPlan
}
