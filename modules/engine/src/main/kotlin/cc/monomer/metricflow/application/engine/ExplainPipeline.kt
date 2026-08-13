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
import cc.monomer.metricflow.domain.plan_conversion.DataflowToSqlPlanConverter
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.TypeGroupedOrderer
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.InputOrderPreservingTypeGroupedOrderer
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.sql.optimizer.SqlOptimizationLevel
import cc.monomer.metricflow.domain.sql.render.SqlPlanRenderer
import cc.monomer.metricflow.domain.sql.render.SqlEngine

/**
 * Wires the W14b explain chain: dataset construction → dataflow plan → SQL plan → SQL string.
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
 *   for the W14c-deferred branches.
 *
 * Each call to [renderSql] re-runs the chain. The wiring is **per-engine, not per-call** in
 * Python; we mirror that by caching the dataflow-plan-builder ingredients lazily.
 *
 * **Dialect selection.** As of W14b the engine doesn't yet accept a dialect argument; this
 * pipeline always renders via the **default** ANSI renderer. Per-dialect routing (Trino /
 * BigQuery / Snowflake / Databricks / Redshift / DuckDB / Postgres) is a downstream concern —
 * see [cc.monomer.metricflow.application.engine.adapter.EngineProtoAdapter] for the
 * gRPC-level dialect lookup that would feed this class once wired.
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
        engine.semanticManifest.semanticModels.map { model ->
            dataSetConverter.createSqlSourceDataSet(model.reference)
        }
    }

    private val timeSpineReadNodes: Map<TimeGranularity, ReadSqlSourceNode> by lazy {
        // Time-spine ReadSqlSourceNode construction relies on
        // TimeSpineSource.buildStandardTimeSpineSources + a dataset wrap. The W14b
        // SIMPLE happy path never needs the time-spine, so we leave the map empty and
        // rely on the builder's W14c deferral when a query asks for it. We do **not**
        // force-evaluate `TimeSpineSource.buildStandardTimeSpineSources` here either —
        // some manifests (e.g. `minimal_valid_manifest`) supply a time-spine with no
        // `relation_name`, which the W1 model preserves verbatim and so the call would
        // throw IllegalArgumentException during eager construction.
        emptyMap()
    }

    private val timeSpineMetricTimeNodes: Map<TimeGranularity, MetricTimeDimensionTransformNode> by lazy {
        emptyMap()
    }

    private val sourceNodeBuilder: SourceNodeBuilder by lazy {
        SourceNodeBuilder(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestGraphLookup = engine.semanticManifestGraphLookup,
            timeSpineReadNodesIn = timeSpineReadNodes,
            timeSpineMetricTimeNodesIn = timeSpineMetricTimeNodes,
        )
    }

    private val sourceNodeSet: SourceNodeSet by lazy {
        sourceNodeBuilder.createFromDataSets(dataSets)
    }

    private val dataflowPlanBuilder: DataflowPlanBuilder by lazy {
        DataflowPlanBuilder(
            sourceNodeSet = sourceNodeSet,
            semanticManifestGraphLookup = engine.semanticManifestGraphLookup,
            columnAssociationResolver = columnAssociationResolver,
            sourceNodeBuilder = sourceNodeBuilder,
            // W9c visitor — the W14c branches will need a strong type, but the W14b SIMPLE
            // pipeline doesn't dereference this. Pass a unit token so the slot is non-null.
            nodeOutputResolver = Unit,
        )
    }

    private val sqlPlanConverter: DataflowToSqlPlanConverter by lazy {
        DataflowToSqlPlanConverter(
            columnAssociationResolver = columnAssociationResolver,
            semanticManifestLookup = engine.semanticManifestLookup,
        )
    }

    /**
     * Render the [querySpec] as a SQL string using the **default** ANSI renderer.
     *
     * Builds the dataflow plan, converts to SQL plan (with full optimizer cascade), then
     * renders via [SqlPlanRenderer]. Per-dialect routing is W14c — the dialect-aware
     * renderer factory + adapter wiring lands when the corpus diff requires it.
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
            renderSqlInScope(querySpec, dialect, outputSelectionSpecs)
        }
    }

    private fun renderSqlInScope(
        querySpec: MetricFlowQuerySpec,
        dialect: SqlEngine,
        outputSelectionSpecs: InstanceSpecSet?,
    ): String {
        val dataflowPlan = dataflowPlanBuilder.buildPlan(
            querySpec = querySpec,
            outputSqlTable = null,
            outputSelectionSpecs = outputSelectionSpecs,
            optimizations = DataflowPlanOptimization.enabledOptimizations(),
        )
        // Choose an OutputColumnOrderer mirroring Python's `MetricFlowEngine.explain` —
        // input-order-preserving by default; otherwise the type-grouped fallback.
        // The orderer also acts as a column allow-list — the WriteToResultDataTableNode visitor
        // emits exactly the columns the orderer produces, which is what drives the
        // SqlColumnPrunerOptimizer to trim unused columns in inner SELECTs.
        val orderer = if (querySpec.inputSpecOrder.groupByItemSpecs.isNotEmpty() ||
            querySpec.inputSpecOrder.metricSpecs.isNotEmpty()) {
            InputOrderPreservingTypeGroupedOrderer(querySpec.inputSpecOrder)
        } else {
            TypeGroupedOrderer()
        }
        val sqlPlanResult = sqlPlanConverter.convertToSqlPlan(
            sqlEngineType = dialect,
            dataflowPlanNode = dataflowPlan.renderNode,
            optimizationLevel = SqlOptimizationLevel.O5,
            sqlQueryPlanId = null,
            outputColumnOrderer = orderer,
        )
        val renderer: SqlPlanRenderer = rendererFor(dialect)
        return renderer.renderSqlPlan(sqlPlanResult.sqlPlan).sql
    }

    /**
     * Pick the dialect-specific renderer. Mirrors Python's
     * `metricflow.sql.render.factory.SqlPlanRendererFactory.create_for_sql_engine`.
     */
    private fun rendererFor(dialect: SqlEngine): SqlPlanRenderer = when (dialect) {
        SqlEngine.TRINO ->
            cc.monomer.metricflow.infrastructure.sql.render.trino.TrinoSqlPlanRenderer()
        SqlEngine.BIGQUERY ->
            cc.monomer.metricflow.infrastructure.sql.render.bigquery.BigQuerySqlPlanRenderer()
        SqlEngine.SNOWFLAKE ->
            cc.monomer.metricflow.infrastructure.sql.render.snowflake.SnowflakeSqlPlanRenderer()
        SqlEngine.DATABRICKS ->
            cc.monomer.metricflow.infrastructure.sql.render.databricks.DatabricksSqlPlanRenderer()
        SqlEngine.REDSHIFT ->
            cc.monomer.metricflow.infrastructure.sql.render.redshift.RedshiftSqlPlanRenderer()
        SqlEngine.DUCKDB ->
            cc.monomer.metricflow.infrastructure.sql.render.duckdb.DuckDbSqlPlanRenderer()
        SqlEngine.POSTGRES ->
            cc.monomer.metricflow.infrastructure.sql.render.postgres.PostgresSqlPlanRenderer()
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
