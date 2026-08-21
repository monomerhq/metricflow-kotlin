package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.domain.dataflow.builder.DataflowPlanBuilder
import cc.monomer.metricflow.domain.dataflow.builder.SourceNodeBuilder
import cc.monomer.metricflow.domain.dataflow.builder.SourceNodeSet
import cc.monomer.metricflow.domain.dataflow.dataset.SemanticModelDataSet
import cc.monomer.metricflow.domain.dataflow.dataset.SemanticModelToDataSetConverter
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimization
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.plan_conversion.DataflowToSqlPlanConverter
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.TypeGroupedOrderer
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.InputOrderPreservingTypeGroupedOrderer
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.InputOrderPreservingOrderer
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.sql.optimizer.SqlOptimizationLevel
import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlEngine

/**
 * Wires the explain chain: dataset construction → dataflow plan → SQL plan → SQL string.
 *
 * Port of the construction-time wiring in
 * `metricflow.engine.metricflow_engine.MetricFlowEngine.__init__`, specifically the path that
 * builds `_source_node_set`, the `DataflowPlanBuilder`, the `DataflowToSqlPlanConverter`, and
 * selects the per-dialect renderer.
 *
 * Construction is lazy / `:application:engine`-local because:
 *
 * - the [SemanticModelToDataSetConverter] needs a [DunderColumnAssociationResolver] which lives
 *   in `:domain:spec`;
 * - the [SourceNodeBuilder] needs the converter's outputs;
 * - the [DataflowPlanBuilder] needs the [SourceNodeBuilder] + the [SemanticModelToDataSetConverter]
 *   for time-spine and metric-evaluation branches.
 *
 * Each call to [renderSql] re-runs the chain. The wiring is **per-engine, not per-call** in
 * Python; we mirror that by caching the dataflow-plan-builder ingredients lazily.
 *
 * **Dialect selection.** The consumer supplies a renderer registry to
 * [MetricFlowEngine]. The pipeline never imports a concrete dialect module.
 * [renderSql] selects the requested dialect renderer through that registry. The same dataflow
 * and SQL plan are used for the supported dialect renderers, including Trino, BigQuery,
 * Snowflake, Databricks, Redshift, DuckDB, Postgres, and the default renderer.
 */
internal class ExplainPipeline(private val engine: MetricFlowEngine) {

    private val columnAssociationResolver = DunderColumnAssociationResolver(
        dunderPrefixSimpleMetricInputs = true,
    )

    private val dataSetConverter = SemanticModelToDataSetConverter(
        columnAssociationResolver = columnAssociationResolver,
        manifestLookup = engine.semanticManifestLookup,
        manifestObjectLookup = engine.semanticManifestGraphLookup.manifestObjectLookup,
    )

    /**
     * All semantic-model datasets, one per model in the manifest.
     *
     * The caller (see [renderSql]) wraps lazy construction in
     * `SequentialIdGenerator.idNumberSpace(INITIALIZER_ID_START)` to mirror Python's
     * `_ID_ENUMERATION_START_VALUE_FOR_INITIALIZER = 10000`. That way each model's
     * `_src_*` alias lands at 10000+ to match the snapshot fixtures.
     */
    private val dataSets: List<SemanticModelDataSet> by lazy {
        engine.semanticManifest.semanticModels.sortedBy { it.name }.map { model ->
            dataSetConverter.createSqlSourceDataSet(model.reference)
        }
    }

    private val timeSpineNodes: Pair<
        Map<TimeGranularity, ReadSqlSourceNode>,
        Map<TimeGranularity, MetricTimeDimensionTransformNode>,
        > by lazy {
        val readNodes = linkedMapOf<TimeGranularity, ReadSqlSourceNode>()
        val metricTimeNodes = linkedMapOf<TimeGranularity, MetricTimeDimensionTransformNode>()
        for ((baseGranularity, timeSpineSource) in engine.semanticManifestLookup.timeSpineSources) {
            val readNode = ReadSqlSourceNode(dataSetConverter.buildTimeSpineSourceDataSet(timeSpineSource))
            readNodes[baseGranularity] = readNode
            metricTimeNodes[baseGranularity] = MetricTimeDimensionTransformNode(
                parentNode = readNode,
                aggregationTimeDimensionReference = TimeDimensionReference(timeSpineSource.baseColumn),
            )
        }
        readNodes to metricTimeNodes
    }

    private val timeSpineReadNodes: Map<TimeGranularity, ReadSqlSourceNode>
        get() = timeSpineNodes.first

    private val timeSpineMetricTimeNodes: Map<TimeGranularity, MetricTimeDimensionTransformNode>
        get() = timeSpineNodes.second

    private val sourceNodeBuilder: SourceNodeBuilder by lazy {
        SourceNodeBuilder(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestGraphLookup = engine.semanticManifestGraphLookup,
            timeSpineReadNodesIn = timeSpineReadNodes,
            timeSpineMetricTimeNodesIn = timeSpineMetricTimeNodes,
        )
    }

    private val sourceNodeSet: SourceNodeSet by lazy {
        // MetricFlow creates semantic-model datasets before SourceNodeBuilder constructs
        // time-spine datasets. Preserve that order because both use the shared initializer
        // ID generator and their aliases are part of rendered SQL.
        val semanticModelDataSets = dataSets
        sourceNodeBuilder.createFromDataSets(semanticModelDataSets)
    }

    private val nodeOutputResolver: DataflowNodeToSqlSubqueryVisitor by lazy {
        DataflowNodeToSqlSubqueryVisitor(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestLookup = engine.semanticManifestLookup,
            outputColumnOrderer = null,
        ).also { it.cacheOutputDataSets(sourceNodeSet.allNodes) }
    }

    private val dataflowPlanBuilder: DataflowPlanBuilder by lazy {
        DataflowPlanBuilder(
            sourceNodeSet = sourceNodeSet,
            semanticManifestGraphLookup = engine.semanticManifestGraphLookup,
            columnAssociationResolver = columnAssociationResolver,
            sourceNodeBuilder = sourceNodeBuilder,
            nodeOutputResolver = nodeOutputResolver,
        )
    }

    private val sqlPlanConverter: DataflowToSqlPlanConverter by lazy {
        DataflowToSqlPlanConverter(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestLookup = engine.semanticManifestLookup,
        )
    }

    /**
     * Render the [querySpec] as a SQL string using the requested [dialect].
     *
     * Builds the dataflow plan, converts to SQL plan (with full optimizer cascade), then
     * renders via [SqlPlanRenderer].
     *
     * @param outputSelectionSpecs optional final-projection spec set. When non-null, the
     *   builder wraps the metric-output node with a [cc.monomer.metricflow.domain
     *   .dataflow.nodes.SelectorNode] that keeps only those specs in the final SELECT.
     *   This is the `MetricFlowQueryType.DIMENSION_VALUES` path — the
     *   `explainGetDimensionValues` RPC drops the metric column at the very end so callers
     *   see only the distinct dimension values. Port of `MetricFlowEngine._create_execution_plan`
     *   lines 590-597 (the `query_type == DIMENSION_VALUES` branch).
     */
    fun renderSql(
        querySpec: MetricFlowQuerySpec,
        dialect: SqlEngine,
        outputSelectionSpecs: InstanceSpecSet?,
        orderOutputColumnsByInputOrder: Boolean,
    ): String {
        // Eagerly construct the dataset / source-node-set / builder inside the
        // initializer ID-scope so that per-model `_src_*` aliases land at 10000+. After
        // this returns, the lazy slots are populated and we can switch into the query
        // scope for subq_*-style aliases.
        @Suppress("UNUSED_VARIABLE")
        val _forceInit = SequentialIdGenerator.idNumberSpace(INITIALIZER_ID_START) {
            dataflowPlanBuilder
        }
        return SequentialIdGenerator.idNumberSpace(QUERY_ID_START) {
            renderSqlInScope(querySpec, dialect, outputSelectionSpecs, orderOutputColumnsByInputOrder)
        }
    }

    private fun renderSqlInScope(
        querySpec: MetricFlowQuerySpec,
        dialect: SqlEngine,
        outputSelectionSpecs: InstanceSpecSet?,
        orderOutputColumnsByInputOrder: Boolean,
    ): String {
        val optimizations = DataflowPlanOptimization.enabledOptimizations()
        val dataflowPlan = dataflowPlanBuilder.buildPlan(
            querySpec = querySpec,
            outputSqlTable = null,
            outputSelectionSpecs = outputSelectionSpecs,
            optimizations = optimizations,
        )
        // Choose an OutputColumnOrderer mirroring Python's `MetricFlowEngine.explain` —
        // input-order-preserving by default; otherwise the type-grouped fallback.
        // The orderer also acts as a column allow-list — the WriteToResultDataTableNode visitor
        // emits exactly the columns the orderer produces, which is what drives the
        // SqlColumnPrunerOptimizer to trim unused columns in inner SELECTs.
        val orderer = when {
            orderOutputColumnsByInputOrder -> InputOrderPreservingOrderer(querySpec.inputSpecOrder)
            DataflowPlanOptimization.PASSTHROUGH_METRIC_EVALUATION in optimizations ->
                InputOrderPreservingTypeGroupedOrderer(querySpec.inputSpecOrder)
            else -> TypeGroupedOrderer()
        }
        val sqlPlanResult = sqlPlanConverter.convertToSqlPlan(
            sqlEngineType = dialect,
            dataflowPlanNode = dataflowPlan.renderNode,
            optimizationLevel = SqlOptimizationLevel.O5,
            sqlQueryPlanId = null,
            outputColumnOrderer = orderer,
        )
        val renderer: SqlPlanRenderer = engine.sqlPlanRendererRegistry.rendererFor(dialect)
        return renderer.renderSqlPlan(sqlPlanResult.sqlPlan).sql
    }

    companion object {
        /**
         * Sequential-ID start value for the initializer scope. Python uses 10000 so dataset
         * column / alias IDs land in a high band that doesn't collide with the per-query IDs.
         * Port of `MetricFlowEngine._ID_ENUMERATION_START_VALUE_FOR_INITIALIZER`.
         */
        private const val INITIALIZER_ID_START: Int = 10000

        /**
         * Sequential-ID start value for per-query construction. Mirrors Python's
         * `_ID_ENUMERATION_START_VALUE_FOR_QUERIES = 0`. Resetting on each query keeps SQL
         * aliases deterministic across calls — critical for the corpus diff.
         */
        private const val QUERY_ID_START: Int = 0
    }
}
