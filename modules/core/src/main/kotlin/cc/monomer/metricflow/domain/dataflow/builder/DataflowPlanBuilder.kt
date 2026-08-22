package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.time.Java8TimePeriodAdjuster
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.domain.dataflow.DataflowPlan
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.dataset.DataSet
import cc.monomer.metricflow.domain.dataflow.dataset.SqlDataSet
import cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.AddGeneratedUuidColumnNode
import cc.monomer.metricflow.domain.dataflow.nodes.AliasSpecsNode
import cc.monomer.metricflow.domain.dataflow.nodes.CombineAggregatedOutputsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode
import cc.monomer.metricflow.domain.dataflow.nodes.ConstrainTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinDescription
import cc.monomer.metricflow.domain.dataflow.nodes.JoinConversionEventsNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOnEntitiesNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinOverTimeRangeNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.JoinToTimeSpineNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.OrderByLimitNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetBaseGrainByCustomGrainNode
import cc.monomer.metricflow.domain.dataflow.nodes.OffsetCustomGranularityNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.dataflow.nodes.SelectorNode
import cc.monomer.metricflow.domain.dataflow.nodes.SpecToAlias
import cc.monomer.metricflow.domain.dataflow.nodes.WhereFilterNode
import cc.monomer.metricflow.domain.dataflow.nodes.WindowReaggregationNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultDataTableNode
import cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimization
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimizerFactory
import cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.plan_conversion.to_sql_plan.DataflowNodeToSqlSubqueryVisitor
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetadataSpec
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.OrderBySpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.EntitySpec
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.spec.bind.SqlJoinType
import cc.monomer.metricflow.domain.spec.bind.SqlTable

/**
 * Builds a [DataflowPlan] to satisfy a given [MetricFlowQuerySpec].
 *
 * Port of `metricflow.dataflow.builder.dataflow_plan_builder.DataflowPlanBuilder`.
 *
 * The constructor mirrors the upstream planner inputs. The SQL subquery visitor resolves the
 * output instance set of intermediate nodes, including shared common-table-expression branches.
 *
 * Metric dispatch covers simple, derived, ratio, cumulative, conversion, and metric-offset
 * queries. Time-dependent branches select a compatible manifest time spine and preserve the
 * upstream placement of source-range expansion, post-join narrowing, and reaggregation.
 *
 * @see buildPlan the query-planning entry point.
 */
class DataflowPlanBuilder(
    private val sourceNodeSet: SourceNodeSet,
    semanticManifestGraphLookup: SemanticManifestGraphLookup,
    private val columnAssociationResolver: ColumnAssociationResolver,
    private val sourceNodeBuilder: SourceNodeBuilder,
    /**
     * The `DataflowNodeToSqlSubqueryVisitor` from W9c (`:domain:plan-conversion`). The visitor
     * answers "what InstanceSet does this node output?", required by the planner's node
     * evaluator.
     */
    private val nodeOutputResolver: DataflowNodeToSqlSubqueryVisitor,
    /** Optional pre-allocated cache; defaults to a fresh empty one. */
    val cache: DataflowPlanBuilderCache,
) {

    /** Convenience constructor that creates a fresh empty cache. */
    constructor(
        sourceNodeSet: SourceNodeSet,
        semanticManifestGraphLookup: SemanticManifestGraphLookup,
        columnAssociationResolver: ColumnAssociationResolver,
        sourceNodeBuilder: SourceNodeBuilder,
        nodeOutputResolver: DataflowNodeToSqlSubqueryVisitor,
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
    private val timePeriodAdjuster = Java8TimePeriodAdjuster()

    /**
     * Build a plan for reading the results of a query into a data table or a result table.
     *
     * Port of `DataflowPlanBuilder.build_plan`.
     *
     * Metric-specific branches are combined before the sink projection, ordering, and limit are
     * applied. Queries that require a time spine fail closed when the manifest has no compatible
     * source; atemporal queries do not require one.
     */
    fun buildPlan(
        querySpec: MetricFlowQuerySpec,
        outputSqlTable: SqlTable?,
        outputSelectionSpecs: InstanceSpecSet?,
        optimizations: Set<DataflowPlanOptimization>,
    ): DataflowPlan {
        metricLookup.validateMetricDefinitionDependencies(
            rootMetricReferences = querySpec.metricSpecs.map { it.reference },
            maximumMetricLevels = MetricEvaluationPlan.MAX_METRIC_DEFINITION_RECURSION_DEPTH,
        )

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
     * Dispatches by metric type:
     *
     * - `SIMPLE` (single metric) → [buildSimpleMetricBranch]
     * - `DERIVED` / `RATIO` → [buildDerivedOrRatioBranch], recursively building non-simple inputs
     *   — inputs sharing a model are merged into one aggregation; cross-model inputs combine
     *   via [CombineAggregatedOutputsNode] (Python `_build_derived_metric_output_node`).
     * - Multi-metric queries → each metric is built independently, then combined via
     *   [CombineAggregatedOutputsNode].
     * - `CUMULATIVE`, `CONVERSION`, standard/custom offsets, and coarse-grain reaggregation use
     *   the manifest time-spine sources and the corresponding dataflow join nodes.
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
            MetricType.CUMULATIVE -> buildCumulativeMetricBranch(
                querySpec = querySpec,
                metricSpec = metricSpec,
                whereFilterSpecs = whereFilterSpecs,
            )
            MetricType.CONVERSION -> buildConversionMetricBranch(
                querySpec = querySpec,
                metricSpec = metricSpec,
                whereFilterSpecs = whereFilterSpecs,
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
            joinToTimeSpineAfterAggregation = simpleMetricInput.joinToTimespine,
        )
    }

    /** Port of MetricFlow's cumulative metric branch. */
    private fun buildCumulativeMetricBranch(
        querySpec: MetricFlowQuerySpec,
        metricSpec: MetricSpec,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        val metric = metricLookup.getMetric(metricSpec.reference)
        val cumulativeParams = checkNotNull(metric.typeParams.cumulativeTypeParams) {
            "Cumulative metric '${metric.name}' is missing cumulative_type_params."
        }
        val inputMetric = checkNotNull(cumulativeParams.metric) {
            "Cumulative metric '${metric.name}' is missing its input metric."
        }
        val simpleInput = manifestObjectLookup.simpleMetricNameToInput[inputMetric.name]
            ?: error("Cumulative input '${inputMetric.name}' is not a simple metric input.")
        val transform = findMetricTimeTransform(
            modelName = simpleInput.modelId.modelName,
            aggregationTimeDimensionName = simpleInput.aggTimeDimensionName,
        ) ?: error("Missing metric-time source for cumulative metric '${metric.name}'.")

        val minMetricTimeSpec = DataSet.metricTimeDimensionSpec(
            ExpandedTimeGranularity.fromTimeGranularity(simpleInput.aggTimeDimensionGrain),
        )
        val queriedAggTimeSpecs = querySpec.timeDimensionSpecs
        val requiresWindowReaggregation = queriedAggTimeSpecs.isNotEmpty() &&
            queriedAggTimeSpecs.none { it.timeGranularity == minMetricTimeSpec.timeGranularity }
        val requiredQuerySpec = if (requiresWindowReaggregation) {
            querySpec.copy(timeDimensionSpecs = queriedAggTimeSpecs + minMetricTimeSpec)
        } else {
            querySpec
        }

        val requestedAggTimeSpecs = requiredQuerySpec.timeDimensionSpecs
        val originalTimeRangeConstraint = querySpec.timeRangeConstraint
        val cumulativeWindow = cumulativeParams.window?.let {
            TimeWindow.createFromDsiTimeWindow(count = it.count, granularity = it.granularity)
        }
        val cumulativeGrainToDate = cumulativeParams.grainToDate?.let(::standardTimeGranularity)
        val expandedSourceConstraint = originalTimeRangeConstraint?.let { constraint ->
            timePeriodAdjuster.expandTimeConstraintForCumulativeMetric(
                timeConstraint = constraint,
                granularity = cumulativeWindow?.granularity?.let(::standardTimeGranularity)
                    ?: cumulativeGrainToDate,
                count = cumulativeWindow?.count ?: if (cumulativeGrainToDate == null) 0 else 1,
            )
        }
        var cumulativeSource: DataflowPlanNode = expandedSourceConstraint?.let { constraint ->
            constrainTimeRangeWithAliasReservation(transform, constraint).also {
                // Upstream resolves the adjusted source recipe and its post-join narrowing
                // selector before final SQL conversion. Both pruned resolutions consume an
                // observable deterministic alias.
                repeat(2) { SequentialIdGenerator.createNextId(StaticIdPrefix.SUB_QUERY) }
            }
        } ?: transform
        if (requestedAggTimeSpecs.isNotEmpty()) {
            val baseSpecs = TimeDimensionSpec.withBaseGrains(requestedAggTimeSpecs).distinct()
            cumulativeSource = JoinOverTimeRangeNode(
                parentNode = cumulativeSource,
                queriedAggTimeDimensionSpecs = baseSpecs,
                window = cumulativeWindow,
                grainToDate = cumulativeGrainToDate,
                timeRangeConstraint = originalTimeRangeConstraint,
            )
        }

        val inputMetricSpec = MetricSpec.fromReference(inputMetric.asReference)
        val aggregatedInput = buildAggregatedNodeForSimpleInputs(
            querySpec = requiredQuerySpec,
            simpleMetricInputNames = listOf(simpleInput.name),
            outputMetricSpecs = listOf(inputMetricSpec),
            outputComputeMetric = false,
            whereFilterSpecs = whereFilterSpecs,
            sourceNodeOverride = cumulativeSource,
            sourceTimeRangeConstraint = null,
            preAggregationTimeRangeConstraint = originalTimeRangeConstraint,
        )
        val computedInput = ComputeMetricsNode.create(
            parentNode = aggregatedInput,
            computedMetricSpecs = listOf(inputMetricSpec),
            passthroughMetricSpecs = emptyList(),
            aggregatedToElements = requiredQuerySpec.linkableSpecs.asTuple.toSet(),
            outputGroupByMetricInstances = false,
        )
        val cumulativeMetricNode = ComputeMetricsNode.create(
            parentNode = computedInput,
            computedMetricSpecs = listOf(metricSpec),
            passthroughMetricSpecs = emptyList(),
            aggregatedToElements = requiredQuerySpec.linkableSpecs.asTuple.toSet(),
            outputGroupByMetricInstances = false,
        )
        return if (requiresWindowReaggregation) {
            WindowReaggregationNode(
                parentNode = cumulativeMetricNode,
                metricSpec = metricSpec,
                orderBySpec = minMetricTimeSpec,
                partitionBySpecs = querySpec.linkableSpecs.asTuple,
            )
        } else {
            cumulativeMetricNode
        }
    }

    private fun standardTimeGranularity(name: String): TimeGranularity =
        TimeGranularity.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }
            ?: error("Expected a standard time granularity, got '$name'.")

    private fun constrainTimeRangeWithAliasReservation(
        parentNode: DataflowPlanNode,
        constraint: TimeRangeConstraint,
    ): ConstrainTimeRangeNode {
        // Python resolves every newly wrapped constraint node while pruning the source recipe.
        // Resolution allocates one deterministic subquery alias even when the optimizer later
        // folds the constraint into its source scan.
        SequentialIdGenerator.createNextId(StaticIdPrefix.SUB_QUERY)
        return ConstrainTimeRangeNode(parentNode = parentNode, timeRangeConstraint = constraint)
    }

    /** Port of MetricFlow's conversion-event branch. */
    private fun buildConversionMetricBranch(
        querySpec: MetricFlowQuerySpec,
        metricSpec: MetricSpec,
        whereFilterSpecs: List<cc.monomer.metricflow.domain.spec.where.WhereFilterSpec>,
    ): DataflowPlanNode {
        val metric = metricLookup.getMetric(metricSpec.reference)
        val params = checkNotNull(metric.typeParams.conversionTypeParams) {
            "Conversion metric '${metric.name}' is missing conversion_type_params."
        }
        val baseMetric = checkNotNull(params.baseMetric) {
            "Conversion metric '${metric.name}' is missing base_metric."
        }
        val conversionMetric = checkNotNull(params.conversionMetric) {
            "Conversion metric '${metric.name}' is missing conversion_metric."
        }
        val baseInput = manifestObjectLookup.simpleMetricNameToInput[baseMetric.name]
            ?: error("Conversion base '${baseMetric.name}' is not a simple metric input.")
        val conversionInput = manifestObjectLookup.simpleMetricNameToInput[conversionMetric.name]
            ?: error("Conversion input '${conversionMetric.name}' is not a simple metric input.")
        val baseTransform = findMetricTimeTransform(
            modelName = baseInput.modelId.modelName,
            aggregationTimeDimensionName = baseInput.aggTimeDimensionName,
        ) ?: error("Missing metric-time source for conversion base '${baseMetric.name}'.")
        val conversionTransform = findMetricTimeTransform(
            modelName = conversionInput.modelId.modelName,
            aggregationTimeDimensionName = conversionInput.aggTimeDimensionName,
        ) ?: error("Missing metric-time source for conversion input '${conversionMetric.name}'.")

        val minMetricTimeSpec = DataSet.metricTimeDimensionSpec(
            cc.monomer.metricflow.common.time.ExpandedTimeGranularity.fromTimeGranularity(
                baseInput.aggTimeDimensionGrain,
            ),
        )
        val entitySpec = EntitySpec(
            elementName = params.entity,
            entityLinks = emptyList(),
            alias = null,
        )
        val baseInputSpec = SimpleMetricInputSpec(elementName = baseInput.name, fillNullsWith = null)
        val conversionInputSpec = SimpleMetricInputSpec(elementName = conversionInput.name, fillNullsWith = null)
        val constantPropertySpecs = params.constantProperties.orEmpty().map { property ->
            cc.monomer.metricflow.domain.spec.ConstantPropertySpec(
                baseSpec = resolveLinkableSpecFromSource(baseTransform, property.baseProperty),
                conversionSpec = resolveLinkableSpecFromSource(conversionTransform, property.conversionProperty),
            )
        }

        val conversionBaseSpecs = InstanceSpecSet.createFromSpecs(
            (
                listOf(baseInputSpec, entitySpec, minMetricTimeSpec) +
                    querySpec.linkableSpecs.asTuple +
                    whereFilterSpecs.flatMap { it.linkableSpecs } +
                    constantPropertySpecs.map { it.baseSpec }
                ).distinct(),
        )
        val constrainedBaseTransform: DataflowPlanNode = querySpec.timeRangeConstraint?.let { constraint ->
            constrainTimeRangeWithAliasReservation(baseTransform, constraint)
        } ?: baseTransform
        var unaggregatedBase: DataflowPlanNode = SelectorNode(
            parentNode = constrainedBaseTransform,
            includeSpecs = conversionBaseSpecs,
            replaceDescription = null,
            distinct = false,
        )
        if (whereFilterSpecs.isNotEmpty()) {
            unaggregatedBase = WhereFilterNode(
                parentNode = unaggregatedBase,
                filterSpecs = whereFilterSpecs,
                alwaysApply = false,
            )
        }
        unaggregatedBase = SelectorNode(
            parentNode = unaggregatedBase,
            includeSpecs = conversionBaseSpecs,
            replaceDescription = null,
            distinct = false,
        )

        val joinConversions = JoinConversionEventsNode(
            baseNode = unaggregatedBase,
            baseTimeDimensionSpec = minMetricTimeSpec,
            conversionNode = AddGeneratedUuidColumnNode(conversionTransform),
            conversionInputMetricSpec = conversionInputSpec,
            conversionTimeDimensionSpec = minMetricTimeSpec,
            uniqueIdentifierKeys = listOf(MetadataSpec(elementName = "mf_internal_uuid", aggType = null)),
            entitySpec = entitySpec,
            window = params.window?.let {
                TimeWindow.createFromDsiTimeWindow(count = it.count, granularity = it.granularity)
            },
            constantProperties = constantPropertySpecs.ifEmpty { null },
        )

        val aggregatedBase = buildAggregatedNodeForSimpleInputs(
            querySpec = querySpec,
            simpleMetricInputNames = listOf(baseInput.name),
            outputMetricSpecs = listOf(MetricSpec.fromReference(baseMetric.asReference)),
            outputComputeMetric = false,
            whereFilterSpecs = whereFilterSpecs,
            sourceNodeOverride = constrainedBaseTransform,
            sourceNodeOverrideLinkableSpecs = querySpec.linkableSpecs.asTuple,
            sourceTimeRangeConstraint = null,
        )
        val aggregatedConversions = buildAggregatedNodeForSimpleInputs(
            querySpec = querySpec,
            simpleMetricInputNames = listOf(conversionInput.name),
            outputMetricSpecs = listOf(MetricSpec.fromReference(conversionMetric.asReference)),
            outputComputeMetric = false,
            whereFilterSpecs = emptyList(),
            sourceNodeOverride = joinConversions,
            sourceNodeOverrideLinkableSpecs = querySpec.linkableSpecs.asTuple,
            sourceTimeRangeConstraint = null,
        )
        return ComputeMetricsNode.create(
            parentNode = CombineAggregatedOutputsNode(
                parentNodes = listOf(aggregatedBase, aggregatedConversions),
            ),
            computedMetricSpecs = listOf(metricSpec),
            passthroughMetricSpecs = emptyList(),
            aggregatedToElements = querySpec.linkableSpecs.asTuple.toSet(),
            outputGroupByMetricInstances = false,
        )
    }

    private fun resolveLinkableSpecFromSource(
        transform: MetricTimeDimensionTransformNode,
        qualifiedName: String,
    ): LinkableInstanceSpec {
        val dataSet = (transform.parentNode as? ReadSqlSourceNode)?.dataSet as? SqlDataSet
            ?: error("Metric-time transform does not read a SQL source dataset.")
        return dataSet.instanceSet.specSet.linkableSpecs.firstOrNull {
            it.dunderName == qualifiedName ||
                (it.elementName == qualifiedName && it.entityLinks.isEmpty())
        } ?: error("Could not resolve conversion constant property '$qualifiedName' in source dataset.")
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
                MetricSpec.create(
                    elementName = im.name,
                    whereFilterSpecs = emptyList(),
                    alias = im.alias,
                    offsetWindow = im.offsetWindow?.let {
                        TimeWindow.createFromDsiTimeWindow(count = it.count, granularity = it.granularity)
                    },
                    offsetToGrain = im.offsetToGrain?.let(::standardTimeGranularity),
                )
            }
            MetricType.RATIO -> {
                val numerator = checkNotNull(metric.typeParams.numerator) {
                    "RATIO metric '${metric.name}' missing numerator (caught in validation)."
                }
                val denominator = checkNotNull(metric.typeParams.denominator) {
                    "RATIO metric '${metric.name}' missing denominator (caught in validation)."
                }
                listOf(
                    MetricSpec.create(
                        elementName = numerator.name,
                        whereFilterSpecs = emptyList(),
                        alias = numerator.alias,
                        offsetWindow = numerator.offsetWindow?.let {
                            TimeWindow.createFromDsiTimeWindow(it.count, it.granularity)
                        },
                        offsetToGrain = numerator.offsetToGrain?.let(::standardTimeGranularity),
                    ),
                    MetricSpec.create(
                        elementName = denominator.name,
                        whereFilterSpecs = emptyList(),
                        alias = denominator.alias,
                        offsetWindow = denominator.offsetWindow?.let {
                            TimeWindow.createFromDsiTimeWindow(it.count, it.granularity)
                        },
                        offsetToGrain = denominator.offsetToGrain?.let(::standardTimeGranularity),
                    ),
                )
            }
            else -> error("buildDerivedOrRatioBranch called with type=$metricType")
        }

        val inputMetrics = inputMetricSpecs.map { inputMetricSpec ->
            metricLookup.getMetric(inputMetricSpec.reference)
        }
        if (inputMetrics.any { it.type != MetricType.SIMPLE }) {
            val inputBranches = inputMetricSpecs.map { inputMetricSpec ->
                buildSingleMetricBranch(
                    querySpec = querySpec,
                    metricSpec = inputMetricSpec,
                    whereFilterSpecs = whereFilterSpecs,
                )
            }
            val combinedInputs = if (inputBranches.size == 1) {
                inputBranches.single()
            } else {
                CombineAggregatedOutputsNode(parentNodes = inputBranches)
            }
            return ComputeMetricsNode.create(
                parentNode = combinedInputs,
                computedMetricSpecs = listOf(metricSpec),
                passthroughMetricSpecs = emptyList(),
                aggregatedToElements = querySpec.linkableSpecs.asTuple.toSet(),
                outputGroupByMetricInstances = false,
            )
        }

        // Resolve each simple input metric to its underlying SimpleMetricInput (model + agg_time_dim).
        data class InputInfo(
            val metricSpec: MetricSpec,
            val simpleInputName: String,
            val modelName: String,
            val aggTimeDim: String,
        )
        val inputInfos = inputMetricSpecs.map { ms ->
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
        val hasOffsets = inputInfos.any { it.metricSpec.hasTimeOffset }

        val baseAggNode: DataflowPlanNode = if (singleModel && !hasOffsets) {
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
                val aggregateNode = buildAggregatedNodeForSimpleInputs(
                    querySpec = querySpec,
                    simpleMetricInputNames = listOf(info.simpleInputName),
                    outputMetricSpecs = listOf(info.metricSpec),
                    outputComputeMetric = !info.metricSpec.hasTimeOffset,
                    whereFilterSpecs = whereFilterSpecs,
                    joinToTimeSpineAfterAggregation =
                        info.metricSpec.standardOffsetWindow != null || info.metricSpec.offsetToGrain != null,
                    offsetWindow = info.metricSpec.standardOffsetWindow,
                    customOffsetWindow = info.metricSpec.customOffsetWindow,
                    offsetToGrain = info.metricSpec.offsetToGrain,
                    sourceTimeRangeConstraint = if (info.metricSpec.hasTimeOffset) {
                        null
                    } else {
                        querySpec.timeRangeConstraint
                    },
                    timeRangeConstraintAfterAggregationTimeSpineJoin =
                        if (info.metricSpec.hasTimeOffset) querySpec.timeRangeConstraint else null,
                )
                if (!info.metricSpec.hasTimeOffset) {
                    aggregateNode
                } else {
                    ComputeMetricsNode.create(
                        parentNode = aggregateNode,
                        computedMetricSpecs = listOf(info.metricSpec),
                        passthroughMetricSpecs = emptyList(),
                        aggregatedToElements = querySpec.linkableSpecs.asTuple.toSet(),
                        outputGroupByMetricInstances = false,
                    )
                }
            }
            if (perInput.size == 1) perInput.single() else CombineAggregatedOutputsNode(parentNodes = perInput)
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
        sourceNodeOverride: DataflowPlanNode? = null,
        sourceNodeOverrideLinkableSpecs: List<LinkableInstanceSpec> = emptyList(),
        joinToTimeSpineAfterAggregation: Boolean = false,
        offsetWindow: TimeWindow? = null,
        customOffsetWindow: TimeWindow? = null,
        offsetToGrain: TimeGranularity? = null,
        sourceTimeRangeConstraint: TimeRangeConstraint? = querySpec.timeRangeConstraint,
        preAggregationTimeRangeConstraint: TimeRangeConstraint? = null,
        timeRangeConstraintAfterAggregationTimeSpineJoin: TimeRangeConstraint? = null,
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
        val localLinkableKeys = collectLocalLinkableKeys(sourceSpecSet).toMutableSet().apply {
            sourceNodeOverrideLinkableSpecs.forEach { spec ->
                add(spec.elementName to spec.entityLinks.map { it.elementName })
            }
        }

        val (localSpecs, joinSpecs) = querySpec.linkableSpecs.asTuple
            .partition { spec -> isLocallyAvailable(spec, localLinkableKeys) }

        var initialSourceNode = sourceNodeOverride ?: transformNode
        var customOffsetAlreadyProvidesCustomGrains = false
        if (customOffsetWindow != null && querySpec.timeDimensionSpecs.isNotEmpty()) {
            val queriedSpecs = querySpec.timeDimensionSpecs
            val useOffsetCustomGranularityNode = queriedSpecs
                .mapNotNull { it.timeGranularityName }
                .toSet() == setOf(customOffsetWindow.granularity)
            val requiredTimeSpineSpecs = if (useOffsetCustomGranularityNode) {
                queriedSpecs
            } else {
                TimeDimensionSpec.withBaseGrains(queriedSpecs)
            }
            val customOffsetBaseGrain = semanticManifestLookup.customGranularities
                .getValue(customOffsetWindow.granularity)
                .baseGranularity
            val expandedBaseGrain = ExpandedTimeGranularity.fromTimeGranularity(customOffsetBaseGrain)
            val joinSpec = if (requiredTimeSpineSpecs.any { it.isMetricTime }) {
                DataSet.metricTimeDimensionSpec(expandedBaseGrain)
            } else {
                requiredTimeSpineSpecs.first().withGrain(expandedBaseGrain)
            }
            val customOffsetSpineSpecs = buildList {
                addAll(requiredTimeSpineSpecs)
                if (joinSpec !in this) add(joinSpec)
            }
            val customOffsetTimeSpineNode = buildCustomOffsetTimeSpineNode(
                offsetWindow = customOffsetWindow,
                requiredSpecs = customOffsetSpineSpecs,
                useOffsetCustomGranularityNode = useOffsetCustomGranularityNode,
            )
            nodeOutputResolver.getOutputDataSet(customOffsetTimeSpineNode)
            initialSourceNode = JoinToTimeSpineNode(
                metricSourceNode = initialSourceNode,
                timeSpineNode = customOffsetTimeSpineNode,
                requestedAggTimeDimensionSpecs = requiredTimeSpineSpecs,
                joinOnTimeDimensionSpec = joinSpec,
                joinType = SqlJoinType.INNER,
                standardOffsetWindow = null,
                offsetToGrain = null,
            )
            customOffsetAlreadyProvidesCustomGrains = useOffsetCustomGranularityNode
        }
        val (sourceNodeForAgg, allAvailableLinkableSpecs) = if (joinSpecs.isEmpty()) {
            // No join needed.
            initialSourceNode to localSpecs
        } else {
            check(sourceNodeOverride == null) {
                "Source override with entity-link joins is not supported by this planner path."
            }
            // Build a JoinOnEntities pipeline.
            buildJoinNodeFor(transformNode, joinSpecs, localSpecs)
        }

        // Apply time-range constraint at the inner pre-aggregation site so the constraint runs
        // before the SELECT projection (Python `_build_pre_aggregation_plan` predicate-pushdown).
        var sourceWithCustomGranularities = sourceNodeForAgg
        for (customSpec in querySpec.timeDimensionSpecs.filter {
            it.hasCustomGrain && !customOffsetAlreadyProvidesCustomGrains
        }) {
            sourceWithCustomGranularities = JoinToCustomGranularityNode(
                parentNode = sourceWithCustomGranularities,
                timeDimensionSpec = customSpec,
            )
        }

        val sourceWithTimeConstraint = sourceTimeRangeConstraint?.let { constraint ->
            constrainTimeRangeWithAliasReservation(sourceWithCustomGranularities, constraint)
        } ?: sourceWithCustomGranularities

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
        val nodeAfterTimeConstraint = preAggregationTimeRangeConstraint?.let { constraint ->
            constrainTimeRangeWithAliasReservation(selectorBeforeConstraints, constraint)
        } ?: selectorBeforeConstraints
        // Add WhereFilterNode between Sel1 and Sel2 when the query carries a non-empty WHERE
        // intersection. Mirrors Python `_build_pre_aggregation_plan` line ~2129-2130.
        val nodeAfterWhere: DataflowPlanNode = if (whereFilterSpecs.isNotEmpty()) {
            WhereFilterNode(
                parentNode = nodeAfterTimeConstraint,
                filterSpecs = whereFilterSpecs,
                alwaysApply = false,
            )
        } else {
            nodeAfterTimeConstraint
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

        var aggregateNode: DataflowPlanNode = AggregateSimpleMetricInputsNode(
            parentNode = selectorForAggregation,
            nullFillValueMapping = nullFillMapping,
        )

        if (joinToTimeSpineAfterAggregation && querySpec.timeDimensionSpecs.isNotEmpty()) {
            val requestedSpecs = querySpec.timeDimensionSpecs
            val joinSpec = requestedSpecs.sortedBy { it.baseGranularitySortKey }.first()
            aggregateNode = JoinToTimeSpineNode(
                metricSourceNode = aggregateNode,
                timeSpineNode = buildTimeSpineNode(
                    requiredSpecs = requestedSpecs,
                    timeRangeConstraint = timeRangeConstraintAfterAggregationTimeSpineJoin,
                ),
                requestedAggTimeDimensionSpecs = requestedSpecs,
                joinOnTimeDimensionSpec = joinSpec,
                joinType = if (offsetWindow == null && offsetToGrain == null) {
                    SqlJoinType.LEFT_OUTER
                } else {
                    SqlJoinType.INNER
                },
                standardOffsetWindow = offsetWindow,
                offsetToGrain = offsetToGrain,
            )
            if (timeRangeConstraintAfterAggregationTimeSpineJoin != null) {
                aggregateNode = constrainTimeRangeWithAliasReservation(
                    aggregateNode,
                    timeRangeConstraintAfterAggregationTimeSpineJoin,
                )
            }
        }

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

    /**
     * Select the configured time-spine source(s) that satisfy [requiredSpecs] and return the
     * smallest compatible metric-time node. Custom grains defined on another configured spine
     * are added through [JoinToCustomGranularityNode], matching MetricFlow 0.210.0's
     * `_build_time_spine_node` source-selection rule.
     */
    private fun buildTimeSpineNode(
        requiredSpecs: List<TimeDimensionSpec>,
        timeRangeConstraint: TimeRangeConstraint? = null,
    ): DataflowPlanNode {
        check(requiredSpecs.isNotEmpty()) { "A time-spine node requires at least one time dimension spec." }
        val selectedSources = TimeSpineSource.chooseTimeSpineSources(
            requiredTimeSpineSpecs = requiredSpecs,
            timeSpineSources = semanticManifestLookup.timeSpineSources,
        )
        val smallestSource = selectedSources.first()
        val readNode = sourceNodeSet.timeSpineReadNodes[smallestSource.baseGranularity]
            ?: error("Missing read node for configured ${smallestSource.baseGranularity} time spine.")
        val readDataSet = readNode.dataSet as? SqlDataSet
            ?: error("Configured time-spine read node must contain a SQL dataset.")
        val customSpecsToJoin = mutableListOf<TimeDimensionSpec>()
        val updatedRequiredSpecs = requiredSpecs.map { spec ->
            if (spec.hasCustomGrain && spec.timeGranularityName !in smallestSource.customGrainNames) {
                customSpecsToJoin += spec
                spec.withBaseGrain()
            } else {
                spec
            }
        }
        val aliasNode = AliasSpecsNode(
            parentNode = readNode,
            changeSpecs = updatedRequiredSpecs.map { outputSpec ->
                SpecToAlias(
                    inputSpec = readDataSet.instanceFromTimeDimensionGrainAndDatePart(
                        timeGranularityName = outputSpec.timeGranularityName,
                        datePart = outputSpec.datePart,
                    ).spec,
                    outputSpec = outputSpec,
                )
            },
        )
        var node: DataflowPlanNode = aliasNode
        for (spec in customSpecsToJoin) {
            node = JoinToCustomGranularityNode(parentNode = node, timeDimensionSpec = spec)
        }
        val selectorBeforeConstraints = SelectorNode(
            parentNode = node,
            includeSpecs = InstanceSpecSet.createFromSpecs(requiredSpecs),
            replaceDescription = null,
            distinct = false,
        )
        val nodeAfterConstraint = timeRangeConstraint?.let { constraint ->
            ConstrainTimeRangeNode(selectorBeforeConstraints, constraint)
        } ?: selectorBeforeConstraints
        return SelectorNode(
            parentNode = nodeAfterConstraint,
            includeSpecs = InstanceSpecSet.createFromSpecs(requiredSpecs),
            replaceDescription = null,
            distinct = false,
        )
    }

    private fun buildCustomOffsetTimeSpineNode(
        offsetWindow: TimeWindow,
        requiredSpecs: List<TimeDimensionSpec>,
        useOffsetCustomGranularityNode: Boolean,
    ): DataflowPlanNode {
        val source = semanticManifestLookup.timeSpineSources.values.firstOrNull {
            offsetWindow.granularity in it.customGrainNames
        } ?: error("No configured time spine defines custom granularity '${offsetWindow.granularity}'.")
        val readNode = sourceNodeSet.timeSpineReadNodes[source.baseGranularity]
            ?: error("Missing read node for custom-granularity time spine '${source.sqlTable.sql}'.")
        return if (useOffsetCustomGranularityNode) {
            OffsetCustomGranularityNode(
                timeSpineNode = readNode,
                offsetWindow = offsetWindow,
                requiredTimeSpineSpecs = requiredSpecs,
            )
        } else {
            OffsetBaseGrainByCustomGrainNode(
                timeSpineNode = readNode,
                offsetWindow = offsetWindow,
                requiredTimeSpineSpecs = requiredSpecs,
            )
        }
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
