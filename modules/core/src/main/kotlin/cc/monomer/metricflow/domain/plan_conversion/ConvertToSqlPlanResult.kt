package cc.monomer.metricflow.domain.plan_conversion

import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.sql.plan.SqlPlan

/**
 * Result of converting a `DataflowPlan` (or sub-node) into a [SqlPlan].
 *
 * Port of `metricflow.plan_conversion.convert_to_sql_plan.ConvertToSqlPlanResult`.
 *
 * The [instanceSet] describes which columns the rendered SQL produces (used by the engine to
 * label results); [sqlPlan] is the SQL DAG that the renderer turns into a text string.
 */
data class ConvertToSqlPlanResult(
    val instanceSet: InstanceSet,
    val sqlPlan: SqlPlan,
)
