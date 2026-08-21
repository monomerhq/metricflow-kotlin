package cc.monomer.metricflow.domain.plan_conversion

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlCteVisitor
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.OutputColumnOrderer
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.sql.optimizer.SqlGenerationOptionSet
import cc.monomer.metricflow.domain.sql.optimizer.SqlOptimizationLevel
import cc.monomer.metricflow.domain.sql.optimizer.SqlPlanOptimizer
import cc.monomer.metricflow.domain.sql.plan.SqlPlan
import cc.monomer.metricflow.domain.sql.plan.SqlPlanNode
import cc.monomer.metricflow.domain.sql.render.SqlEngine

/**
 * Top-level entry point for the dataflow→SQL conversion layer.
 *
 * Port of `metricflow.plan_conversion.to_sql_plan.dataflow_to_sql.DataflowToSqlPlanConverter`.
 *
 * Walks a [DataflowPlanNode] (typically a plan's sink) via [DataflowNodeToSqlSubqueryVisitor],
 * applies the chosen [SqlPlanOptimizer] pipeline, and produces a [ConvertToSqlPlanResult]
 * carrying both the resulting [cc.monomer.metricflow.domain.dataflow.instance.InstanceSet]
 * and the rendered [SqlPlan].
 *
 * Construction precomputes the standard + custom time-spine sources from the manifest. The
 * heavy lifting happens in [convertToSqlPlan], which retries at progressively lower
 * optimization levels if a bug at a higher tier raises an exception (preserves the Python
 * defence-in-depth behaviour — see the `try / except` cascade in the upstream module).
 *
 * **Status — public surface in W9c.** The converter wiring + retry loop are fully ported. The
 * underlying [DataflowNodeToSqlSubqueryVisitor] body is deferred to W10 (see that class's
 * KDoc); calling [convertToSqlPlan] today raises `NotImplementedError` from inside the
 * visitor. The optimizer cascade itself is real — wiring exists so tests can drive the
 * conversion path once the visitor body lands.
 */
class DataflowToSqlPlanConverter(
    val columnAssociationResolver: ColumnAssociationResolver,
    private val semanticManifestLookup: SemanticManifestLookup,
) {
    private val metricLookup = semanticManifestLookup.metricLookup
    private val semanticModelLookup = semanticManifestLookup.semanticModelLookup
    private val timeSpineSources =
        TimeSpineSource.buildStandardTimeSpineSources(semanticManifestLookup.semanticManifest)
    private val customGranularityTimeSpineSources =
        TimeSpineSource.buildCustomTimeSpineSources(timeSpineSources.values.toList())

    /**
     * Create a SQL plan that represents the computation up to [dataflowPlanNode].
     *
     * Port of `convert_to_sql_plan`. Iterates over the optimization tiers from
     * [optimizationLevel] down to `O1`, falling back on exception (so a latent bug at a high
     * tier doesn't crash the user — they get less-optimised but correct SQL).
     */
    fun convertToSqlPlan(
        sqlEngineType: SqlEngine,
        dataflowPlanNode: DataflowPlanNode,
        optimizationLevel: SqlOptimizationLevel,
        sqlQueryPlanId: DagId?,
        outputColumnOrderer: OutputColumnOrderer?,
    ): ConvertToSqlPlanResult {
        // Build the descending list of optimization levels to try. Skip O0 unless explicitly
        // requested — the column pruner only kicks in from O2 upward, and without it the
        // generated SQL can be enormous.
        val candidates = SqlOptimizationLevel.entries
            .filter { it >= SqlOptimizationLevel.O1 && it <= optimizationLevel }
            .toMutableSet()
        candidates += optimizationLevel
        val optimizationLevelsToAttempt = candidates.sortedDescending()

        var retriedAtLowerOptimizationLevel = false
        var lastException: Exception? = null

        for (attemptedLevel in optimizationLevelsToAttempt) {
            try {
                val useColumnAliasInGroupBy = sqlEngineType == SqlEngine.BIGQUERY
                val optionSet = SqlGenerationOptionSet.optionsForLevel(
                    level = attemptedLevel,
                    useColumnAliasInGroupBy = useColumnAliasInGroupBy,
                )

                // The Python implementation chooses CTEs via `_get_nodes_to_convert_to_cte`.
                // Preserve that choice here so common dataflow branches are shared in the SQL
                // plan instead of being emitted repeatedly as nested subqueries.
                val nodesToConvertToCte = if (optionSet.allowCte) {
                    getNodesToConvertToCte(dataflowPlanNode)
                } else {
                    emptySet()
                }
                val result = convertUsingSpecifics(
                    dataflowPlanNode = dataflowPlanNode,
                    sqlQueryPlanId = sqlQueryPlanId,
                    nodesToConvertToCte = nodesToConvertToCte,
                    optimizers = optionSet.optimizers,
                    outputColumnOrderer = outputColumnOrderer,
                )

                if (retriedAtLowerOptimizationLevel) {
                    // Python logs at ERROR — we keep the silence since SLF4J wiring is module-
                    // local. The caller still gets a correct plan.
                }
                return result
            } catch (e: Exception) {
                lastException = e
                if (attemptedLevel == optimizationLevelsToAttempt.last()) throw e
                retriedAtLowerOptimizationLevel = true
            }
        }

        throw lastException
            ?: error("Should have returned a result or raised an exception in the loop.")
    }

    /**
     * Helper that runs a single attempt at a fixed optimization-level option set.
     *
     * Port of `convert_using_specifics`. Mainly useful for tests that want to pin the
     * optimization tier deterministically.
     */
    fun convertUsingSpecifics(
        dataflowPlanNode: DataflowPlanNode,
        sqlQueryPlanId: DagId?,
        nodesToConvertToCte: Set<DataflowPlanNode>,
        optimizers: List<SqlPlanOptimizer>,
        outputColumnOrderer: OutputColumnOrderer?,
    ): ConvertToSqlPlanResult {
        val dataSet: SqlDataSet = if (nodesToConvertToCte.isEmpty()) {
            val toSqlSubqueryVisitor = DataflowNodeToSqlSubqueryVisitor(
                columnAssociationResolver = columnAssociationResolver,
                semanticManifestLookup = semanticManifestLookup,
                outputColumnOrderer = outputColumnOrderer,
            )
            toSqlSubqueryVisitor.getOutputDataSet(dataflowPlanNode)
        } else {
            val toSqlCteVisitor = DataflowNodeToSqlCteVisitor(
                columnAssociationResolver = columnAssociationResolver,
                semanticManifestLookup = semanticManifestLookup,
                nodesToConvertToCte = nodesToConvertToCte,
                outputColumnOrderer = outputColumnOrderer,
            )
            toSqlCteVisitor.getOutputDataSetWithCtes(dataflowPlanNode)
        }

        var sqlNode: SqlPlanNode = dataSet.sqlNode
        for (optimizer in optimizers) {
            sqlNode = optimizer.optimize(sqlNode)
        }

        val planId = sqlQueryPlanId ?: DagId.fromIdPrefix(StaticIdPrefix.SQL_PLAN_PREFIX)
        return ConvertToSqlPlanResult(
            instanceSet = dataSet.instanceSet,
            sqlPlan = SqlPlan(renderNode = sqlNode, planId = planId),
        )
    }

    /**
     * Helper for selecting which dataflow nodes should be converted to CTEs. Port of
     * `_get_nodes_to_convert_to_cte`.
     */
    private fun getNodesToConvertToCte(
        dataflowPlanNode: DataflowPlanNode,
    ): Set<DataflowPlanNode> {
        val dataflowPlan = dataflowPlanNode.asPlan()
        return cc.monomer.metricflow.domain.dataflow.DataflowPlanAnalyzer
            .findCommonBranches(dataflowPlan)
            .asSequence()
            .toSet()
    }
}
