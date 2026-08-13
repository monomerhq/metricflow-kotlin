package cc.monomer.metricflow.domain.sql.optimizer

import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode

/**
 * A single pass that rewrites a SQL plan into an equivalent (or simpler) plan.
 *
 * Port of `metricflow.sql.optimizer.sql_query_plan_optimizer.SqlPlanOptimizer`. Concrete
 * passes ([SqlTableAliasSimplifier], [SqlColumnPrunerOptimizer], [SqlRewritingSubQueryReducer])
 * implement this. The pipeline driver [SqlPlanOptimizerPipeline] chains them.
 */
interface SqlPlanOptimizer {

    /** Apply the optimization pass to [node] and return the rewritten tree. */
    fun optimize(node: SqlPlanNode): SqlPlanNode
}
