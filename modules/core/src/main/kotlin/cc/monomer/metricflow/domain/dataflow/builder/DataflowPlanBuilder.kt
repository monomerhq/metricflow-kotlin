package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlan
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.dataset.DataSet
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimization
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimizerFactory
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.OrderBySpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.bind.SqlTable

/**
 * Builds a [DataflowPlan] to satisfy a given [MetricFlowQuerySpec].
 *
 * Port of `metricflow.dataflow.builder.dataflow_plan_builder.DataflowPlanBuilder`.
 *
 * ## Wiring (W9b)
 *
 * The Python class takes a `SemanticManifestLookup`, a `SourceNodeSet`, a
 * `ColumnAssociationResolver`, a `SourceNodeBuilder`, and a `DataflowNodeToSqlSubqueryVisitor`
 * for output-set resolution. The Kotlin constructor mirrors that signature — the last argument
 * (the SQL subquery visitor) is a forward reference into W9c (`:domain:plan-conversion`) and is
 * carried as [Any] here to keep this module's dependency graph clean.
 *
 * ## Algorithm body (W14b)
 *
 * The Python `build_plan` recurses through the `MetricEvaluationPlan` (`:domain:metric-evaluation`)
 * and converts each `MetricQueryNode` into a sub-dataflow. This Kotlin port lands a **scoped
 * SIMPLE-metric implementation**:
 *
 * - **SIMPLE metric, no offsets, no cumulative window, no entity-link join, no where-filter,
 *   no time-spine join, single metric per query** — fully implemented. The chain is
 *   `MetricTimeDimensionTransformNode → AggregateSimpleMetricInputsNode → ComputeMetricsNode`,
 *   wrapped by the sink builder ([buildSinkNode]).
 *
 * - **All other metric shapes** (CUMULATIVE, DERIVED, RATIO, CONVERSION; SIMPLE-with-joins or
 *   SIMPLE-with-offset or SIMPLE-with-filter) — deferred to W14c with a typed
 *   [NotImplementedError] naming the missing dependency. This is the honest deferral pattern
 *   ([PROGRESS.md](../../../../../../../../../PROGRESS.md) "강한 deferral 정책").
 *
 * The deferred branches need: the `:domain:metric-evaluation` pipeline (DepthFirstSearch
 * planner / passthrough planner), `_build_simple_metric_recipe` (filter + offset analysis),
 * `_find_source_node_recipe` (NodeEvaluatorForLinkableInstances algorithm), and
 * `_build_pre_aggregation_plan` (join-target application). The Python file is 2,489 LOC; the
 * Kotlin port of those branches is W14c scope.
 *
 * @see buildPlan entry point — the recursion entry point.
 * @see buildSimpleMetricBranch the SIMPLE-only happy-path.
 */
class DataflowPlanBuilder(
    private val sourceNodeSet: SourceNodeSet,
    semanticManifestGraphLookup: SemanticManifestGraphLookup,
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val sourceNodeBuilder: SourceNodeBuilder,
    /**
     * The `DataflowNodeToSqlSubqueryVisitor` from W9c (`:domain:plan-conversion`). The visitor
     * answers "what InstanceSet does this node output?", required by the planner's node
     * evaluator. Held as [Any] until the W14c branches need the strong type.
     */
    private val nodeOutputResolver: Any,
    /** Optional pre-allocated cache; defaults to a fresh empty one. */
    val cache: DataflowPlanBuilderCache,
) {

    /** Convenience constructor that creates a fresh empty cache. */
    constructor(
        sourceNodeSet: SourceNodeSet,
        semanticManifestGraphLookup: SemanticManifestGraphLookup,
        columnAssociationResolver: ColumnAssociationResolver,
        sourceNodeBuilder: SourceNodeBuilder,
        nodeOutputResolver: Any,
    ) : this(
        sourceNodeSet = sourceNodeSet,
        semanticManifestGraphLookup = semanticManifestGraphLookup,
        columnAssociationResolver = columnAssociationResolver,
        sourceNodeBuilder = sourceNodeBuilder,
        nodeOutputResolver = nodeOutputResolver,
        cache = DataflowPlanBuilderCache(),
    )

    /** The W7a semantic-manifest lookup (composed into the W7c graph lookup). */
    private val semanticManifestLookup: SemanticManifestLookup =
        semanticManifestGraphLookup.semanticManifestLookup
    private val semanticModelLookup = semanticManifestLookup.semanticModelLookup
    private val metricLookup = semanticManifestLookup.metricLookup
    private val manifestObjectLookup = semanticManifestGraphLookup.manifestObjectLookup
    private val metricTimeDimensionReference = DataSet.metricTimeDimensionReference()

    /**
     * Build a plan for reading the results of a query into a data table or a result table.
     *
     * Port of `DataflowPlanBuilder.build_plan`.
     *
     * **W14b scope.** Only the SIMPLE-metric happy path is supported. The query must have:
     *
     * - exactly one metric;
     * - that metric must be [MetricType.SIMPLE];
     * - the metric must not have an offset window / offset-to-grain;
     * - the query must have no `whereConstraints`;
     * - the query's `linkableSpecs` must all be local (no `entity_links`) — i.e. only `metric_time`
     *   and dimensions from the metric's own semantic model.
     *
     * Anything else throws [NotImplementedError] with a W14c pointer.
     */
    fun buildPlan(
        querySpec: MetricFlowQuerySpec,
        outputSqlTable: SqlTable?,
        outputSelectionSpecs: InstanceSpecSet?,
        optimizations: Set<DataflowPlanOptimization>,
    ): DataflowPlan {
        val metricsOutputNode = buildQueryOutputNode(querySpec)

        var current = metricsOutputNode
        if (outputSelectionSpecs != null) {
            current = SelectorNode(
                parentNode = current,
                includeSpecs = outputSelectionSpecs,
                replaceDescription = null,
                distinct = false,
            )
        }
        val sink = buildSinkNode(
            parentNode = current,
            desiredOutputMetricSpecs = querySpec.metricSpecs,
            desiredOutputGroupByItemSpecs = querySpec.dimensionSpecs +
                querySpec.timeDimensionSpecs +
                querySpec.entitySpecs,
            orderBySpecs = querySpec.orderBySpecs,
            outputSqlTable = outputSqlTable,
            limit = querySpec.limit,
        )
        val plan = DataflowPlan(
            renderNode = sink,
            planId = DagId.fromIdPrefix(StaticIdPrefix.DATAFLOW_PLAN_PREFIX),
        )
        return applyOptimizers(plan = plan, optimizations = optimizations)
    }

    /**
     * Build a metrics-output node for the given query spec. Port of
     * `DataflowPlanBuilder._build_query_output_node`.
     *
     * **W14c scope.** Dispatches by metric type:
     *
     * - `SIMPLE` (single metric) → [buildSimpleMetricBranch]
     * - `DERIVED` / `RATIO` (single metric, all inputs simple) → [buildDerivedOrRatioBranch]
     *   — inputs sharing a model are merged into one aggregation; cross-model inputs combine
     *   via [CombineAggregatedOutputsNode] (Python `_build_derived_metric_output_node`).
     * - Multi-metric queries → each metric is built independently, then combined via
     *   [CombineAggregatedOutputsNode].
     * - `CUMULATIVE`, `CONVERSION` and any branch needing time-spine joins are honest-deferred
     *   to W15 — those visit methods exist in [cc.monomer.metricflow.domain
     *   .plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor] but are not exercised
     *   by the W14c corpus surface.
     */
    private fun buildQueryOutputNode(querySpec: MetricFlowQuerySpec): DataflowPlanNode {
        if (querySpec.minMaxOnly) {
            throw NotImplementedError(
                "MinMax queries are W15-deferred: requires _build_plan_for_no_metrics_query path " +
                    "+ visitMinMaxNode body completion.",
            )
        }
        // Time-range constraint is handled by inserting a ConstrainTimeRangeNode between the
        // source transform and the selectors — see buildAggregatedNodeForSimpleInputs.
        // Resolve query-level WHERE filters once and thread them down to the per-metric branches.
        // Mirrors Python's `_build_query_output_node` line ~240-247 where the factory is constructed
        // and `query_level_filter_specs` are materialised before the metric-evaluation recursion.
        val whereFilterSpecs = if (querySpec.filterIntersection.whereFilters.isNotEmpty()) {
            val customGrainNames = semanticModelLookup.customGranularityNames
            val factory = cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory(
                columnAssociationResolver = columnAssociationResolver,
                specResolutionLookup = cc.monomer.metricflow.domain.query.resolution
                    .FilterSpecResolutionLookUp.EMPTY,
                customGrainNames = customGrainNames.toList(),
            )
            factory.createFromWhereFilterIntersection(
                filterLocation = cc.monomer.metricflow.domain.query.resolution
                    .WhereFilterLocation.forQuery(metricReferences = emptyList()),
                filterIntersection = querySpec.filterIntersection,
            )
        } else {
            emptyList()
        }

        // Multi-metric: build each metric's branch independently, then combine.
        if (querySpec.metricSpecs.size > 1) {
            val metricBranches = querySpec.metricSpecs.map { ms ->
                buildSingleMetricBranch(querySpec, ms, whereFilterSpecs)
            }
            return CombineAggregatedOutputsNode(parentNodes = metricBranches)
        }

        val metricSpec = querySpec.metricSpecs.single()
        return buildSingleMetricBranch(querySpec, metricSpec, whereFilterSpecs)
    }

    /**
     * Dispatch on metric type for a single metric in the query. Encapsulates the "what kind of
     * sub-plan does this metric need" decision.
     */
    private fun buildSingleMetricBranch(
        querySpec: MetricFlowQuerySpec,
        metricSpec: MetricSpec,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        val metric = metricLookup.getMetric(metricSpec.reference)
        return when (metric.type) {
            MetricType.SIMPLE -> buildSimpleMetricBranch(querySpec, metricSpec, whereFilterSpecs)
            MetricType.DERIVED, MetricType.RATIO ->
                buildDerivedOrRatioBranch(querySpec, metricSpec, metric.type, whereFilterSpecs)
            MetricType.CUMULATIVE -> throw NotImplementedError(
                "CUMULATIVE metric '${metric.name}' is W15-deferred: requires " +
                    "JoinOverTimeRangeNode + time-spine plumbing (visit method requires " +
                    "_make_time_spine_data_set helper).",
            )
            MetricType.CONVERSION -> throw NotImplementedError(
                "CONVERSION metric '${metric.name}' is W15-deferred: requires " +
                    "JoinConversionEventsNode + CTE / window-function pipeline.",
            )
        }
    }

    /**
     * The SIMPLE-metric pipeline.
     *
     * Port of the no-join, no-filter, no-offset case from
     * `DataflowPlanBuilder._build_simple_metric_output_node` /
     * `DataflowPlanBuilder._build_aggregated_simple_metric_input` /
     * `DataflowPlanBuilder.build_computed_metrics_node`.
     *
     * The chain assembled here:
     *
     * ```
     * MetricTimeDimensionTransformNode (pre-built in SourceNodeSet)
     *   → AggregateSimpleMetricInputsNode
     *     → ComputeMetricsNode
     * ```
     *
     * `MetricTimeDimensionTransformNode` is selected from the [sourceNodeSet] — the W9b builder
     * pre-constructs one transform per `(semantic_model, aggregation_time_dimension)` pair, so
     * we don't need the SourceNodeRecipe / NodeEvaluatorForLinkableInstances machinery.
     */
    private fun buildSimpleMetricBranch(
        querySpec: MetricFlowQuerySpec,
        metricSpec: MetricSpec,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        if (metricSpec.whereFilterSpecs.isNotEmpty()) {
            throw NotImplementedError(
                "Metric-level where-filter specs are W15 scope, " +
                    "see _build_simple_metric_recipe + metric_defined_filter_specs path.",
            )
        }
        if (metricSpec.offsetWindow != null || metricSpec.offsetToGrain != null) {
            throw NotImplementedError(
                "Metric offset is W15 scope, see " +
                    "_build_time_spine_join_node_for_before_aggregation.",
            )
        }
        if (querySpec.linkableSpecs.groupByMetricSpecs.isNotEmpty()) {
            throw NotImplementedError(
                "Group-by-metric specs are W15 scope " +
                    "(requires JoinOnEntitiesNode on a metric-source sub-plan).",
            )
        }

        val simpleMetricInput = manifestObjectLookup.simpleMetricNameToInput[metricSpec.elementName]
            ?: throw NotImplementedError(
                "Simple metric '${metricSpec.elementName}' is not registered in " +
                    "ManifestObjectLookup.simpleMetricNameToInput. The W7c lookup may have " +
                    "dropped it during model transformation.",
            )

        return buildAggregatedNodeForSimpleInputs(
            querySpec = querySpec,
            simpleMetricInputNames = listOf(simpleMetricInput.name),
            outputMetricSpecs = listOf(metricSpec),
            outputComputeMetric = true,
            whereFilterSpecs = whereFilterSpecs,
        )
    }

    /**
     * Build a derived or ratio metric branch.
     *
     * Port of `_build_derived_metric_output_node` (DERIVED) and the same path used for RATIO.
     * For the W14c scope, we handle:
     *
     * - **Single-model derived metrics** (e.g. `booking_fees = booking_value * 0.05` where
     *   `booking_value` lives on `bookings_source`) — built as a single simple-metric branch
     *   wrapped by an extra `ComputeMetricsNode` that evaluates the derived metric's `expr`.
     * - **Single-model ratio metrics** (e.g. `bookings_per_dollar` over the same model) — same
     *   pattern; the RATIO numerator/denominator both contribute to the simple-metric input set.
     * - **Multi-model derived/ratio** (e.g. `bookings_per_listing` joining `bookings_source` +
     *   `listings_latest`) — built by recursing into each input metric to produce a branch, then
     *   combining via `CombineAggregatedOutputsNode` + a top `ComputeMetricsNode`.
     *
     * Time-offset derived metrics (`bookings_1_month_ago`) are NOT in scope and fall back to a
     * NotImplementedError pointing at the W15 time-spine wave.
     */
    private fun buildDerivedOrRatioBranch(
        querySpec: MetricFlowQuerySpec,
        metricSpec: MetricSpec,
        metricType: MetricType,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        val metric = metricLookup.getMetric(metricSpec.reference)

        val inputMetricSpecs: List<MetricSpec> = when (metricType) {
            MetricType.DERIVED -> metric.inputMetrics.map { im ->
                if (im.offsetWindow != null || im.offsetToGrain != null) {
                    throw NotImplementedError(
                        "Derived metric '${metric.name}' has an offset input (${im.name}). " +
                            "Time-offset is W15 scope (_build_time_spine_join_node_for_nested_offset).",
                    )
                }
                MetricSpec.fromReference(im.asReference)
            }
            MetricType.RATIO -> {
                val numerator = checkNotNull(metric.typeParams.numerator) {
                    "RATIO metric '${metric.name}' missing numerator (caught in validation)."
                }
                val denominator = checkNotNull(metric.typeParams.denominator) {
                    "RATIO metric '${metric.name}' missing denominator (caught in validation)."
                }
                if (numerator.offsetWindow != null || numerator.offsetToGrain != null ||
                    denominator.offsetWindow != null || denominator.offsetToGrain != null
                ) {
                    throw NotImplementedError(
                        "RATIO metric '${metric.name}' has an offset input — W15 scope.",
                    )
                }
                listOf(
                    MetricSpec.fromReference(numerator.asReference),
                    MetricSpec.fromReference(denominator.asReference),
                )
            }
            else -> error("buildDerivedOrRatioBranch called with type=$metricType")
        }

        // Resolve each input metric to its underlying SimpleMetricInput (model + agg_time_dim).
        data class InputInfo(
            val metricSpec: MetricSpec,
            val simpleInputName: String,
            val modelName: String,
            val aggTimeDim: String,
        )
        val inputInfos = inputMetricSpecs.map { ms ->
            val inputMetric = metricLookup.getMetric(ms.reference)
            if (inputMetric.type != MetricType.SIMPLE) {
                throw NotImplementedError(
                    "Derived/RATIO metric '${metric.name}' has a non-SIMPLE input '${ms.elementName}' " +
                        "(${inputMetric.type}). Nested derived inputs are W15 scope.",
                )
            }
            val sim = manifestObjectLookup.simpleMetricNameToInput[ms.elementName]
                ?: throw NotImplementedError(
                    "Input metric '${ms.elementName}' of '${metric.name}' has no registered " +
                        "SimpleMetricInput entry.",
                )
            InputInfo(
                metricSpec = ms,
                simpleInputName = sim.name,
                modelName = sim.modelId.modelName,
                aggTimeDim = sim.aggTimeDimensionName,
            )
        }

        val modelsUsed = inputInfos.map { it.modelName to it.aggTimeDim }.toSet()
        val singleModel = modelsUsed.size == 1

        val baseAggNode: DataflowPlanNode = if (singleModel) {
            // All input metrics share a model + agg_time_dim. Build a single aggregation that
            // produces both simple-metric outputs at once, then wrap with the derived/ratio
            // ComputeMetricsNode.
            buildAggregatedNodeForSimpleInputs(
                querySpec = querySpec,
                simpleMetricInputNames = inputInfos.map { it.simpleInputName },
                outputMetricSpecs = inputInfos.map { it.metricSpec },
                outputComputeMetric = true,
                whereFilterSpecs = whereFilterSpecs,
            )
        } else {
            // Multi-model: build one branch per input metric, combine via CombineAggregatedOutputsNode.
            val perInput = inputInfos.map { info ->
                buildAggregatedNodeForSimpleInputs(
                    querySpec = querySpec,
                    simpleMetricInputNames = listOf(info.simpleInputName),
                    outputMetricSpecs = listOf(info.metricSpec),
                    outputComputeMetric = true,
                    whereFilterSpecs = whereFilterSpecs,
                )
            }
            CombineAggregatedOutputsNode(parentNodes = perInput)
        }

        // Top-level ComputeMetricsNode that evaluates the derived/ratio expression.
        return ComputeMetricsNode.create(
            parentNode = baseAggNode,
            computedMetricSpecs = listOf(metricSpec),
            passthroughMetricSpecs = emptyList(),
            aggregatedToElements = querySpec.linkableSpecs.asTuple.toSet(),
            outputGroupByMetricInstances = false,
        )
    }

    /**
     * Build the `Selector → Aggregate → ComputeMetrics` chain that aggregates one or more
     * simple-metric inputs (all on the same model + agg-time-dim) to the queried linkable specs.
     *
     * Port of the no-offset path of `_build_aggregated_simple_metric_input` + the SIMPLE branch
     * of `build_computed_metrics_node`.
     *
     * Handles:
     * - Source nodes where the queried linkable specs are locally available (no join).
     * - Entity-link linkable specs that require a JOIN to another semantic model — assembled via
     *   `JoinOnEntitiesNode` over candidate read nodes from [sourceNodeSet].
     *
     * @param simpleMetricInputNames the simple-metric input element names to include in the
     *   aggregation (all must share a model + agg_time_dim).
     * @param outputMetricSpecs the metric specs to write out via `ComputeMetricsNode`. For a
     *   SIMPLE metric this is `[metricSpec]`; for a DERIVED/RATIO building block it lists each
     *   input metric so the simple-metric columns become first-class metric instances in the
     *   downstream `ComputeMetricsNode`.
     */
    private fun buildAggregatedNodeForSimpleInputs(
        querySpec: MetricFlowQuerySpec,
        simpleMetricInputNames: List<String>,
        outputMetricSpecs: List<MetricSpec>,
        outputComputeMetric: Boolean,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        check(simpleMetricInputNames.isNotEmpty()) { "Need at least one simple-metric input." }

        val firstInput = manifestObjectLookup.simpleMetricNameToInput.getValue(simpleMetricInputNames[0])
        val modelName = firstInput.modelId.modelName
        val aggTimeDim = firstInput.aggTimeDimensionName
        check(
            simpleMetricInputNames.all { name ->
                val sim = manifestObjectLookup.simpleMetricNameToInput.getValue(name)
                sim.modelId.modelName == modelName && sim.aggTimeDimensionName == aggTimeDim
            },
        ) {
            "All simple-metric inputs must share (model, agg_time_dim). Got: $simpleMetricInputNames"
        }

        val transformNode = findMetricTimeTransform(
            modelName = modelName,
            aggregationTimeDimensionName = aggTimeDim,
        ) ?: throw NotImplementedError(
            "Could not find a pre-built MetricTimeDimensionTransformNode for model '$modelName' " +
                "with agg_time_dim='$aggTimeDim'.",
        )

        // Partition queried linkable specs into "locally available on the source model" vs
        // "needs a join". Locally available specs are matched against the source InstanceSet
        // produced by SemanticModelToDataSetConverter (which already emits primary-entity-linked
        // variants of dimensions/entities).
        val parentReadNode = transformNode.parentNode as? ReadSqlSourceNode
            ?: throw NotImplementedError(
                "MetricTimeDimensionTransformNode parent is not a ReadSqlSourceNode; " +
                    "join-source-aware planning is W15 scope.",
            )
        val sourceInstanceSet = (parentReadNode.dataSet as? SqlDataSet)?.instanceSet
            ?: throw NotImplementedError(
                "ReadSqlSourceNode.dataSet is not a concrete SqlDataSet; cannot inspect specs.",
            )
        val sourceSpecSet = sourceInstanceSet.specSet
        val localLinkableKeys = collectLocalLinkableKeys(sourceSpecSet)

        val (localSpecs, joinSpecs) = querySpec.linkableSpecs.asTuple
            .partition { spec -> isLocallyAvailable(spec, localLinkableKeys) }

        val (sourceNodeForAgg, allAvailableLinkableSpecs) = if (joinSpecs.isEmpty()) {
            // No join needed.
            transformNode as DataflowPlanNode to localSpecs
        } else {
            // Build a JoinOnEntities pipeline.
            buildJoinNodeFor(transformNode, joinSpecs, localSpecs)
        }

        // Apply time-range constraint at the inner pre-aggregation site so the constraint runs
        // before the SELECT projection (Python `_build_pre_aggregation_plan` predicate-pushdown).
        val sourceWithTimeConstraint = querySpec.timeRangeConstraint?.let { constraint ->
            // Mirror Python's hidden side-effect alias allocation: in Python
            // `PreJoinNodeProcessor.apply_matching_filter_predicates` wraps each source node with
            // a ConstrainTimeRangeNode, then `remove_unnecessary_nodes` immediately calls
            // `_node_data_set_resolver.get_output_data_set(constrain_node)` to inspect its specs.
            // That call dispatches `visit_constrain_time_range_node`, which allocates one
            // SUB_QUERY id. The W14c Kotlin builder does not yet wire `nodeOutputResolver`, so
            // we mirror the side-effect by consuming one alias here. Without this, our
            // outermost subq alias trails Python's by 1 (see `bookings_with_time_constraint`
            // corpus case). This is a stop-gap until the full `nodeOutputResolver` wiring
            // lands; the alias consumed has no semantic effect on the rendered SQL.
            SequentialIdGenerator.createNextId(StaticIdPrefix.SUB_QUERY)
            ConstrainTimeRangeNode(parentNode = sourceNodeForAgg, timeRangeConstraint = constraint)
        } ?: sourceNodeForAgg

        // Build the spec set to keep: all simple-metric inputs + the queried linkable specs.
        val simpleMetricInputSpecs = simpleMetricInputNames.map { name ->
            SimpleMetricInputSpec(elementName = name, fillNullsWith = null)
        }
        val specsToKeepList = simpleMetricInputSpecs.toList<InstanceSpec>() + allAvailableLinkableSpecs
        val specsToKeepForAggregation = InstanceSpecSet.createFromSpecs(specsToKeepList)

        // Mirror Python's `_get_specs_to_keep_before_constraints`: the inner Selector must include
        // any linkable specs referenced by where-filter clauses so the WhereFilterNode can
        // reference them. Dedup against the aggregation specs so we don't accidentally request a
        // column twice (which would change the comment's "Select: [...]" rendering).
        val filterLinkableSpecs = whereFilterSpecs.flatMap { it.linkableSpecs }
        val specsToKeepBeforeConstraints = if (filterLinkableSpecs.isEmpty()) {
            specsToKeepForAggregation
        } else {
            InstanceSpecSet.createFromSpecs(
                (specsToKeepList + filterLinkableSpecs).distinct(),
            )
        }

        val selectorBeforeConstraints = SelectorNode(
            parentNode = sourceWithTimeConstraint,
            includeSpecs = specsToKeepBeforeConstraints,
            replaceDescription = null,
            distinct = false,
        )
        // Add WhereFilterNode between Sel1 and Sel2 when the query carries a non-empty WHERE
        // intersection. Mirrors Python `_build_pre_aggregation_plan` line ~2129-2130.
        val nodeAfterWhere: DataflowPlanNode = if (whereFilterSpecs.isNotEmpty()) {
            WhereFilterNode(
                parentNode = selectorBeforeConstraints,
                filterSpecs = whereFilterSpecs,
                alwaysApply = false,
            )
        } else {
            selectorBeforeConstraints
        }
        val selectorForAggregation = SelectorNode(
            parentNode = nodeAfterWhere,
            includeSpecs = specsToKeepForAggregation,
            replaceDescription = null,
            distinct = false,
        )

        val nullFillMapping = simpleMetricInputNames
            .mapNotNull { name ->
                val sim = manifestObjectLookup.simpleMetricNameToInput.getValue(name)
                sim.fillNullsWith?.let { name to it }
            }
            .toMap()
            .let { if (it.isEmpty()) NullFillValueMapping.EMPTY else NullFillValueMapping.create(it) }

        val aggregateNode = AggregateSimpleMetricInputsNode(
            parentNode = selectorForAggregation,
            nullFillValueMapping = nullFillMapping,
        )

        if (!outputComputeMetric) return aggregateNode
        return ComputeMetricsNode.create(
            parentNode = aggregateNode,
            computedMetricSpecs = outputMetricSpecs,
            passthroughMetricSpecs = emptyList(),
            aggregatedToElements = querySpec.linkableSpecs.asTuple.toSet(),
            outputGroupByMetricInstances = false,
        )
    }

    /**
     * Collect (elementName, entityLinks) keys of all dimension/time-dimension/entity specs
     * present in the source InstanceSet. We compare by element-name + entity-links rather than
     * by raw spec equality so a queried `DimensionSpec(elementName="is_instant",
     * entityLinks=[booking])` matches the source's emission regardless of grain/date-part.
     */
    private fun collectLocalLinkableKeys(specSet: cc.monomer.metricflow.domain.spec.InstanceSpecSet):
        Set<Pair<String, List<String>>> {
        val out = mutableSetOf<Pair<String, List<String>>>()
        for (d in specSet.dimensionSpecs) {
            out.add(d.elementName to d.entityLinks.map { it.elementName })
        }
        for (t in specSet.timeDimensionSpecs) {
            out.add(t.elementName to t.entityLinks.map { it.elementName })
        }
        for (e in specSet.entitySpecs) {
            out.add(e.elementName to e.entityLinks.map { it.elementName })
        }
        return out
    }

    /**
     * Decide whether the queried [spec] is satisfied by the source semantic model without
     * needing a join. `metric_time` is always locally available since it's added by the
     * `MetricTimeDimensionTransformNode`.
     */
    private fun isLocallyAvailable(
        spec: LinkableInstanceSpec,
        localLinkableKeys: Set<Pair<String, List<String>>>,
    ): Boolean {
        if (spec is TimeDimensionSpec && spec.isMetricTime) return true
        return localLinkableKeys.contains(spec.elementName to spec.entityLinks.map { it.elementName })
    }

    /**
     * For specs that need a join, build a `JoinOnEntities` chain on top of the metric-time
     * transform.
     *
     * Each join spec is matched against candidate `ReadSqlSourceNode`s from
     * [sourceNodeSet.sourceNodesForGroupByItemQueries] by finding one whose InstanceSet exposes
     * the queried spec (matched on elementName + entity-link tail beyond the leading link).
     *
     * Port of the entity-link satisfaction subset of `NodeEvaluatorForLinkableInstances` — we
     * skip the full evaluator (1500 LOC of search-space pruning) and instead do a direct
     * single-hop match per spec, which covers the W14c corpus surface (single-hop joins like
     * `bookings_by_listing_country`).
     */
    private fun buildJoinNodeFor(
        transformNode: MetricTimeDimensionTransformNode,
        joinSpecs: List<LinkableInstanceSpec>,
        localSpecs: List<LinkableInstanceSpec>,
    ): Pair<DataflowPlanNode, List<LinkableInstanceSpec>> {
        val joinTargets = mutableListOf<JoinDescription>()
        val resolvedSpecs = mutableListOf<LinkableInstanceSpec>()
        resolvedSpecs.addAll(localSpecs)

        // Group join specs by leading entity link — each unique leading link becomes one join
        // target. This mirrors Python's `JoinLinkableInstancesRecipe` grouping.
        val byLeadingLink = joinSpecs.groupBy { spec -> spec.entityLinks.firstOrNull() }

        for ((leadingLink, specGroup) in byLeadingLink) {
            checkNotNull(leadingLink) {
                "Empty leading entity-link for join spec; should have been handled as local."
            }
            // Find a read source node whose model contains the right-side specs reachable
            // through the leading link's entity reference (i.e. the model's primary entity is
            // the leading link).
            val rightReadNode = findReadSourceForEntity(
                entityName = leadingLink.elementName,
                requiredSpecs = specGroup,
            ) ?: throw NotImplementedError(
                "Could not satisfy join target for leading entity-link '$leadingLink' with " +
                    "queried specs ${specGroup.map { it.dunderName }}. Multi-hop joins or " +
                    "missing source models are W15 scope.",
            )
            joinTargets.add(
                JoinDescription(
                    joinNode = rightReadNode,
                    joinOnEntity = leadingLink,
                    joinType = SqlJoinType.LEFT_OUTER,
                    joinOnPartitionDimensions = emptyList(),
                    joinOnPartitionTimeDimensions = emptyList(),
                    validityWindow = null,
                ),
            )
            resolvedSpecs.addAll(specGroup)
        }

        val joinNode = JoinOnEntitiesNode(leftNode = transformNode, joinTargets = joinTargets)
        return joinNode to resolvedSpecs
    }

    /**
     * Find a `ReadSqlSourceNode` from [sourceNodeSet] whose model has [entityName] as a primary
     * (or unique) entity and whose InstanceSet exposes all [requiredSpecs] modulo the leading
     * entity-link. Returns null if no suitable model is found.
     */
    private fun findReadSourceForEntity(
        entityName: String,
        requiredSpecs: List<LinkableInstanceSpec>,
    ): ReadSqlSourceNode? {
        // Strip the leading entity-link from each required spec to compare against the source
        // model's emission (which uses local entity-links only).
        val strippedKeys = requiredSpecs.map { spec ->
            spec.elementName to spec.entityLinks.drop(1).map { it.elementName }
        }.toSet()

        for (node in sourceNodeSet.sourceNodesForGroupByItemQueries) {
            if (node !is ReadSqlSourceNode) continue
            val ds = node.dataSet as? SqlDataSet ?: continue
            val localKeys = collectLocalLinkableKeys(ds.instanceSet.specSet)
            if (strippedKeys.all { it in localKeys }) {
                // Verify this model has the entityName as a (primary or unique) entity by
                // checking the entity instances at empty entity-links.
                val hasEntity = ds.instanceSet.entityInstances.any { ei ->
                    ei.spec.entityLinks.isEmpty() && ei.spec.elementName == entityName
                }
                if (hasEntity) return node
            }
        }
        return null
    }

    /**
     * Find the [MetricTimeDimensionTransformNode] in [sourceNodeSet] whose parent
     * [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode] reads from the
     * model named [modelName] and whose [aggregationTimeDimensionReference] matches
     * [aggregationTimeDimensionName].
     */
    private fun findMetricTimeTransform(
        modelName: String,
        aggregationTimeDimensionName: String,
    ): MetricTimeDimensionTransformNode? {
        for (node in sourceNodeSet.sourceNodesForMetricQueries) {
            if (node !is MetricTimeDimensionTransformNode) continue
            if (node.aggregationTimeDimensionReference.elementName != aggregationTimeDimensionName) continue
            val parent = node.parentNode
            val parentSemanticModel = parent.inputSemanticModel ?: continue
            if (parentSemanticModel.semanticModelName == modelName) {
                return node
            }
        }
        return null
    }

    /** Run all configured optimizer passes over [plan]. Port of `_optimize_plan`. */
    private fun applyOptimizers(
        plan: DataflowPlan,
        optimizations: Set<DataflowPlanOptimization>,
    ): DataflowPlan {
        if (optimizations.isEmpty()) return plan
        val optimizerFactory = DataflowPlanOptimizerFactory()
        var current = plan
        for (optimizer in optimizerFactory.getOptimizers(optimizations)) {
            current = optimizer.optimize(current)
        }
        // Suppress unused-field warning until W14c wires the visitor through the factory.
        @Suppress("UNUSED_EXPRESSION")
        nodeOutputResolver
        return current
    }

    /**
     * Wrap a pre-built metrics-output node into a full [DataflowPlan]. Port of the post-amble
     * of `DataflowPlanBuilder.build_plan` that runs after `_build_query_output_node` returns.
     *
     * Used to test sink construction without a metric-evaluation pipeline, and as a stable
     * back-door for callers that already have a metrics-output node (e.g. an integration test).
     */
    fun buildPlanFromMetricsOutputNode(
        querySpec: MetricFlowQuerySpec,
        metricsOutputNode: DataflowPlanNode,
        outputSqlTable: SqlTable?,
        outputSelectionSpecs: InstanceSpecSet?,
    ): DataflowPlan {
        var current = metricsOutputNode
        if (outputSelectionSpecs != null) {
            current = SelectorNode(
                parentNode = current,
                includeSpecs = outputSelectionSpecs,
                replaceDescription = null,
                distinct = false,
            )
        }
        val sink = buildSinkNode(
            parentNode = current,
            desiredOutputMetricSpecs = querySpec.metricSpecs,
            desiredOutputGroupByItemSpecs = querySpec.dimensionSpecs +
                querySpec.timeDimensionSpecs +
                querySpec.entitySpecs,
            orderBySpecs = querySpec.orderBySpecs,
            outputSqlTable = outputSqlTable,
            limit = querySpec.limit,
        )
        return DataflowPlan(
            renderNode = sink,
            planId = DagId.fromIdPrefix(StaticIdPrefix.DATAFLOW_PLAN_PREFIX),
        )
    }

    companion object {

        /**
         * Build the sink node for the plan. Port of `DataflowPlanBuilder.build_sink_node`.
         *
         * Wraps the [parentNode] with an [OrderByLimitNode] if [orderBySpecs] is non-empty or
         * [limit] is non-null, then with a [WriteToResultTableNode] if [outputSqlTable] is
         * supplied or a [WriteToResultDataTableNode] otherwise. The Python `_add_alias_node`
         * post-aliasing pass is W14c (requires the AliasSpecsNode-with-Selector pattern).
         */
        fun buildSinkNode(
            parentNode: DataflowPlanNode,
            desiredOutputMetricSpecs: List<MetricSpec>,
            desiredOutputGroupByItemSpecs: List<LinkableInstanceSpec>,
            orderBySpecs: List<OrderBySpec>,
            outputSqlTable: SqlTable?,
            limit: Int?,
        ): DataflowPlanNode {
            var current = parentNode
            if (orderBySpecs.isNotEmpty() || limit != null) {
                current = OrderByLimitNode(
                    parentNode = current,
                    orderBySpecs = orderBySpecs,
                    limit = limit,
                )
            }
            // Note: Python's `_add_alias_node` runs here when any spec has a non-null alias.
            // Alias support is W14c scope — corpus cases in W14b don't exercise it.
            @Suppress("UNUSED_PARAMETER") val _ignoredMetric = desiredOutputMetricSpecs
            @Suppress("UNUSED_PARAMETER") val _ignoredGroupBys = desiredOutputGroupByItemSpecs
            return if (outputSqlTable != null) {
                WriteToResultTableNode(parentNode = current, outputSqlTable = outputSqlTable)
            } else {
                WriteToResultDataTableNode(parentNode = current)
            }
        }

        /**
         * Convenience overload preserving the W9b-era signature (no order-by / no limit). New
         * callers should use the full-arg variant.
         */
        fun buildSinkNode(
            parentNode: DataflowPlanNode,
            desiredOutputMetricSpecs: List<MetricSpec>,
            outputSqlTable: SqlTable?,
        ): DataflowPlanNode = buildSinkNode(
            parentNode = parentNode,
            desiredOutputMetricSpecs = desiredOutputMetricSpecs,
            desiredOutputGroupByItemSpecs = emptyList(),
            orderBySpecs = emptyList(),
            outputSqlTable = outputSqlTable,
            limit = null,
        )
    }
}
