package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.common.time.Java8TimePeriodAdjuster
import cc.monomer.metricflow.common.time.TimeRangeConstraint
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilter
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.query.filter.DefaultWhereFilterPatternFactory
import cc.monomer.metricflow.domain.query.filter.WhereFilterPatternFactory
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.GroupByItemResolutionDag
import cc.monomer.metricflow.domain.query.input.ResolverInputForApplyGroupBy
import cc.monomer.metricflow.domain.query.input.ResolverInputForGroupByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForLimit
import cc.monomer.metricflow.domain.query.input.ResolverInputForMetric
import cc.monomer.metricflow.domain.query.input.ResolverInputForMinMaxOnly
import cc.monomer.metricflow.domain.query.input.ResolverInputForOrderByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForQuery
import cc.monomer.metricflow.domain.query.input.ResolverInputForQueryLevelWhereFilterIntersection
import cc.monomer.metricflow.domain.query.naming.DunderNamingScheme
import cc.monomer.metricflow.domain.query.naming.MetricNamingScheme
import cc.monomer.metricflow.domain.query.naming.ObjectBuilderNamingScheme
import cc.monomer.metricflow.domain.query.naming.QueryItemLocation
import cc.monomer.metricflow.domain.query.naming.QueryItemNamingScheme
import cc.monomer.metricflow.domain.query.parameter.GroupByQueryParameter
import cc.monomer.metricflow.domain.query.parameter.MetricQueryParameter
import cc.monomer.metricflow.domain.query.parameter.OrderByQueryParameter
import cc.monomer.metricflow.domain.query.resolution.MetricFlowQueryResolution
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec

/**
 * The top-level query parser.
 *
 * Port of `metricflow_semantics.query.query_parser.MetricFlowQueryParser`.
 *
 * The Python class converts user-supplied query inputs into resolver
 * inputs, runs the resolver, and packages the result as a
 * [ParseQueryResult]. The Kotlin port preserves the surface and the
 * ordered naming-scheme list, but **the actual resolver body is deferred
 * to W9** — the resolver pipeline (push-down candidate intersection,
 * filter-spec resolution, validation rule scheduling) spans ~1500 LOC of
 * mutually recursive code in `metricflow_semantics.query.query_resolver`,
 * which has hard dependencies on the W7c DFS path enumerator. W9 will
 * land the resolver alongside the dataflow planner that consumes its
 * output.
 *
 * What this class delivers today:
 *
 * - The same construction surface as Python — two `naming_scheme` tuples
 *   (`metricNamingSchemes`, `groupByItemNamingSchemes`), a configurable
 *   [WhereFilterPatternFactory], and a [SemanticManifestLookup].
 * - The [convertInputsToResolverInputForQuery] method, which converts
 *   raw user inputs (metric-name strings, group-by-name strings, etc.)
 *   into a [ResolverInputForQuery] — useful in isolation for testing the
 *   naming-scheme dispatch.
 * - The DAG-building entry point [buildResolutionDag] for the
 *   group-by-item resolution DAG, which is fully buildable today.
 * - A [build_query_spec_for_group_by_metric_source_node] equivalent
 *   ([buildQuerySpecForGroupByMetricSourceNode]) — see W9 caller plan.
 *
 * The full `parseAndValidateQuery` method is intentionally **not**
 * exposed: it requires `MetricFlowQueryResolver.resolveQuery` which
 * lands in W9.
 */
class MetricFlowQueryParser(
    private val semanticManifestLookup: SemanticManifestLookup,
    private val graphLookup: SemanticManifestGraphLookup?,
    private val whereFilterPatternFactory: WhereFilterPatternFactory,
) {
    /**
     * Convenience constructor that defaults the [WhereFilterPatternFactory]
     * to [DefaultWhereFilterPatternFactory] — call site stays explicit
     * (mirrors how `MetricFlowQueryRequest` keeps the user's API surface
     * tight without using default parameter values).
     */
    constructor(semanticManifestLookup: SemanticManifestLookup) : this(
        semanticManifestLookup = semanticManifestLookup,
        graphLookup = null,
        whereFilterPatternFactory = DefaultWhereFilterPatternFactory(),
    )

    /**
     * Construction surface for callers that already hold the
     * [SemanticManifestGraphLookup] (which the resolver needs). Mirrors how
     * [cc.monomer.metricflow.application.engine.MetricFlowEngine]
     * keeps the graph alive across RPC calls.
     */
    constructor(
        semanticManifestLookup: SemanticManifestLookup,
        graphLookup: SemanticManifestGraphLookup,
    ) : this(
        semanticManifestLookup = semanticManifestLookup,
        graphLookup = graphLookup,
        whereFilterPatternFactory = DefaultWhereFilterPatternFactory(),
    )

    /** Naming schemes tried, in order, when interpreting a metric name. */
    private val metricNamingSchemes: List<QueryItemNamingScheme> =
        listOf(MetricNamingScheme(), ObjectBuilderNamingScheme())

    /** Naming schemes tried, in order, when interpreting a group-by-item name. */
    private val groupByItemNamingSchemes: List<QueryItemNamingScheme> =
        listOf(ObjectBuilderNamingScheme(), DunderNamingScheme())

    /**
     * Convert raw query inputs into a [ResolverInputForQuery] record.
     *
     * Mirrors the body of Python's `_parse_and_validate_query` up to (but
     * not including) `query_resolver.resolve_query`.
     *
     * - String inputs are routed through each naming scheme in order; the
     *   first scheme whose `inputStrFollowsScheme` returns `true` wins.
     * - Object-style parameters (`MetricParameter`, `DimensionOrEntityParameter`,
     *   `TimeDimensionParameter`, `OrderByParameter`) delegate to their
     *   built-in `queryResolverInput` factory.
     */
    fun convertInputsToResolverInputForQuery(
        metricNames: List<String>,
        metrics: List<MetricQueryParameter>,
        groupByNames: List<String>,
        groupBy: List<GroupByQueryParameter>,
        whereConstraints: List<WhereFilter>,
        orderBy: List<OrderByQueryParameter>,
        orderByNames: List<String>,
        limit: Int?,
        minMaxOnly: Boolean,
        applyGroupBy: Boolean,
    ): ResolverInputForQuery {
        val metricInputs = mutableListOf<ResolverInputForMetric>()
        for (metricName in metricNames) {
            val scheme = metricNamingSchemes.firstOrNull {
                it.inputStrFollowsScheme(metricName, semanticManifestLookup, QueryItemLocation.NON_ORDER_BY)
            } ?: throw IllegalArgumentException("Metric name '$metricName' does not match any known naming scheme.")
            metricInputs += ResolverInputForMetric(
                inputObj = metricName,
                namingScheme = scheme,
                specPattern = scheme.specPattern(metricName, semanticManifestLookup, QueryItemLocation.NON_ORDER_BY),
                alias = null,
            )
        }
        for (metric in metrics) metricInputs += metric.queryResolverInput(semanticManifestLookup)

        val groupByInputs = mutableListOf<ResolverInputForGroupByItem>()
        for (groupByName in groupByNames) {
            val scheme = groupByItemNamingSchemes.firstOrNull {
                it.inputStrFollowsScheme(groupByName, semanticManifestLookup, QueryItemLocation.NON_ORDER_BY)
            } ?: throw IllegalArgumentException("Group-by name '$groupByName' does not match any known naming scheme.")
            groupByInputs += ResolverInputForGroupByItem(
                inputObj = groupByName,
                inputObjNamingScheme = scheme,
                specPattern = scheme.specPattern(groupByName, semanticManifestLookup, QueryItemLocation.NON_ORDER_BY),
                alias = null,
            )
        }
        for (groupByParameter in groupBy) groupByInputs += groupByParameter.queryResolverInput(semanticManifestLookup)

        val whereFilterInput = ResolverInputForQueryLevelWhereFilterIntersection(
            whereFilterIntersection = WhereFilterIntersection(whereFilters = whereConstraints),
        )

        val orderByInputs: MutableList<ResolverInputForOrderByItem> = mutableListOf()
        orderByInputs += parseOrderByNames(orderByNames)
        orderByInputs += orderBy.map { it.queryResolverInput(semanticManifestLookup) }

        return ResolverInputForQuery(
            metricInputs = metricInputs,
            groupByItemInputs = groupByInputs,
            filterInput = whereFilterInput,
            orderByItemInputs = orderByInputs,
            limitInput = ResolverInputForLimit(limit),
            minMaxOnly = ResolverInputForMinMaxOnly(minMaxOnly),
            applyGroupBy = ResolverInputForApplyGroupBy(applyGroupBy),
        )
    }

    /**
     * Build a group-by-item resolution DAG for the given query inputs.
     *
     * Mirrors the resolver-internal step that constructs the DAG; this
     * surface is useful for tooling that wants to inspect the DAG without
     * running the rest of the resolver pipeline.
     */
    fun buildResolutionDag(
        resolverInputForQuery: ResolverInputForQuery,
    ): GroupByItemResolutionDag = cc.monomer.metricflow.domain.query
        .group_by.resolution_dag.GroupByItemResolutionDagBuilder(semanticManifestLookup)
        .build(
            metricReferences = resolverInputForQuery.metricInputs.map {
                cc.monomer.metricflow.domain.manifest.model.references.MetricReference(
                    it.inputObj.toString(),
                )
            },
            whereFilterIntersection = resolverInputForQuery.filterInput.whereFilterIntersection,
        )

    /**
     * Result record for a parsed query.
     *
     * Port of `metricflow_semantics.query.query_parser.ParseQueryResult`.
     *
     * Today reachable only via the W9 resolver wiring; included here for
     * call-site stability.
     */
    data class ParseQueryResult(
        val querySpec: MetricFlowQuerySpec,
        val queriedSemanticModels: List<cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference>,
    )

    /**
     * Full entry point.
     *
     * **W14a status — body landed.** As of W14a, the resolver body is implemented (see
     * [MetricFlowQueryResolver]). The chain is:
     *
     * 1. [convertInputsToResolverInputForQuery] turns user input into resolver-input records.
     * 2. [MetricFlowQueryResolver.resolveQuery] resolves metric specs, group-by specs,
     *    order-by specs, and runs validation rules.
     * 3. Returns a [MetricFlowQueryResolution] which contains a non-null `querySpec`
     *    if resolution succeeded.
     *
     * Callers that hold only a [SemanticManifestLookup] (no graph) get a single
     * deferral: the graph-backed group-by resolution requires the W7c
     * [SemanticManifestGraphLookup]. Use the [constructor] variant that takes a
     * `graphLookup` to unlock the full pipeline; the engine wires this up in
     * [cc.monomer.metricflow.application.engine.MetricFlowEngine].
     *
     * The downstream `DataflowPlanBuilder.buildPlan` body is still W14b deferred —
     * the engine catches the resulting [NotImplementedError] at that boundary so the
     * diff-runner categorises explain-target cases as UNIMPLEMENTED rather than ERROR.
     */
    fun parseAndValidateQuery(
        metricNames: List<String>,
        metrics: List<MetricQueryParameter>,
        groupByNames: List<String>,
        groupBy: List<GroupByQueryParameter>,
        limit: Int?,
        timeConstraintStart: java.time.LocalDateTime?,
        timeConstraintEnd: java.time.LocalDateTime?,
        whereConstraints: List<WhereFilter>,
        whereConstraintStrs: List<String>,
        orderByNames: List<String>,
        orderBy: List<OrderByQueryParameter>,
        minMaxOnly: Boolean,
        applyGroupBy: Boolean,
    ): MetricFlowQueryResolution {
        // Port of Python's `MetricFlowQueryParser._parse_and_validate_query`: bare-string where
        // constraints are wrapped into the canonical `WhereFilter(where_sql_template=...)` object
        // before resolution. The corpus exercises this via `request.json.where_constraints`
        // (string array) for the `bookings_with_where` case.
        val combinedWhereConstraints = whereConstraints + whereConstraintStrs.map { WhereFilter(it) }
        val resolverInput = convertInputsToResolverInputForQuery(
            metricNames = metricNames,
            metrics = metrics,
            groupByNames = groupByNames,
            groupBy = groupBy,
            whereConstraints = combinedWhereConstraints,
            orderBy = orderBy,
            orderByNames = orderByNames,
            limit = limit,
            minMaxOnly = minMaxOnly,
            applyGroupBy = applyGroupBy,
        )
        val effectiveGraphLookup = graphLookup
            ?: throw NotImplementedError(
                "MetricFlowQueryParser.parseAndValidateQuery requires a SemanticManifestGraphLookup " +
                    "for group-by resolution. Use the constructor variant that accepts a graphLookup " +
                    "(MetricFlowEngine already wires this up; callers that only hold a " +
                    "SemanticManifestLookup must do the same).",
            )
        val resolver = MetricFlowQueryResolver(
            manifestLookup = semanticManifestLookup,
            graphLookup = effectiveGraphLookup,
            whereFilterPatternFactory = whereFilterPatternFactory,
        )
        val resolution = resolver.resolveQuery(resolverInput)
        if (timeConstraintStart == null && timeConstraintEnd == null) return resolution

        val querySpec = resolution.querySpec ?: return resolution
        val requestedMetricTimeGrain = querySpec.timeDimensionSpecs
            .filter { it.isMetricTime && it.baseGranularity != null }
            .minByOrNull { it.baseGranularitySortKey }
            ?.baseGranularity
        val effectiveMetricTimeGrain = requestedMetricTimeGrain
            ?: querySpec.metricSpecs
                .map { minimumQueryableTimeGranularity(it.reference, effectiveGraphLookup) }
                .maxByOrNull(TimeGranularity::toInt)
            ?: effectiveGraphLookup.manifestObjectLookup.minTimeGrainUsedInModels
            ?: error("Unable to resolve a metric-time grain for a constrained query.")
        val timeRangeConstraint = TimeRangeConstraint(
            startTime = timeConstraintStart ?: TimeRangeConstraint.ALL_TIME_BEGIN,
            endTime = timeConstraintEnd ?: TimeRangeConstraint.ALL_TIME_END,
        )
        val adjustedConstraint = Java8TimePeriodAdjuster().expandTimeConstraintToFillGranularity(
            timeConstraint = timeRangeConstraint,
            granularity = effectiveMetricTimeGrain,
        )
        return resolution.copy(querySpec = querySpec.withTimeRangeConstraint(adjustedConstraint))
    }

    private fun minimumQueryableTimeGranularity(
        metricReference: MetricReference,
        graphLookup: SemanticManifestGraphLookup,
    ): TimeGranularity {
        graphLookup.manifestObjectLookup.simpleMetricNameToInput[metricReference.elementName]?.let {
            return it.aggTimeDimensionGrain
        }
        val metric = semanticManifestLookup.metricLookup.getMetric(metricReference)
        val inputMetrics = MetricLookup.metricInputs(metric, includeConversionMetricInput = true)
        check(inputMetrics.isNotEmpty()) {
            "Expected a non-simple metric to have inputs: ${metricReference.elementName}"
        }
        return inputMetrics
            .map { minimumQueryableTimeGranularity(MetricReference(it.name), graphLookup) }
            .maxBy(TimeGranularity::toInt)
    }

    /**
     * Parse raw order-by name strings (e.g. `"metric_time__day"` or `"-metric_time__day"` for
     * descending) into [ResolverInputForOrderByItem] entries.
     *
     * Port of `MetricFlowQueryParser._parse_order_by_names`. The Kotlin port covers the dunder-
     * and metric-naming-scheme path used by the corpus (object-builder syntax for order-by is a
     * later polish item — none of the corpus order-by names use `Dimension(...)`-style syntax).
     */
    private fun parseOrderByNames(orderByNames: List<String>): List<ResolverInputForOrderByItem> {
        if (orderByNames.isEmpty()) return emptyList()
        val out = mutableListOf<ResolverInputForOrderByItem>()
        for (raw in orderByNames) {
            val descending = raw.startsWith("-")
            val name = if (descending) raw.substring(1) else raw
            val possible = mutableListOf<cc.monomer.metricflow.domain.query.input.MetricFlowQueryResolverInput>()

            for (scheme in groupByItemNamingSchemes) {
                if (scheme.inputStrFollowsScheme(
                        name, semanticManifestLookup, QueryItemLocation.ORDER_BY,
                    )
                ) {
                    val specPattern = scheme.specPattern(
                        name, semanticManifestLookup, QueryItemLocation.ORDER_BY,
                    )
                    possible += ResolverInputForGroupByItem(
                        inputObj = raw,
                        inputObjNamingScheme = scheme,
                        specPattern = specPattern,
                        alias = null,
                    )
                }
            }
            for (scheme in metricNamingSchemes) {
                if (scheme.inputStrFollowsScheme(
                        name, semanticManifestLookup, QueryItemLocation.ORDER_BY,
                    )
                ) {
                    val specPattern = scheme.specPattern(
                        name, semanticManifestLookup, QueryItemLocation.ORDER_BY,
                    )
                    possible += ResolverInputForMetric(
                        inputObj = raw,
                        namingScheme = scheme,
                        specPattern = specPattern,
                        alias = null,
                    )
                }
            }
            out += ResolverInputForOrderByItem(
                inputObj = raw,
                possibleInputs = possible,
                descending = descending,
            )
        }
        return out
    }

    @Suppress("UNUSED_PARAMETER")
    fun buildQuerySpecForGroupByMetricSourceNode(
        groupByMetricSpec: cc.monomer.metricflow.domain.spec.GroupByMetricSpec,
    ): MetricFlowQuerySpec {
        throw NotImplementedError(
            "buildQuerySpecForGroupByMetricSourceNode requires the W14 resolver body — see parseAndValidateQuery.",
        )
    }
}

/**
 * A user-visible request to the parser, packaged as a single record.
 *
 * Mirrors Python's keyword-argument explosion on `parseAndValidateQuery`.
 * Kotlin call sites are expected to construct one of these explicitly per
 * CLAUDE.md's "no default param values" rule.
 *
 * Equivalent of the implicit "query request" that the Python parser
 * accepts via keyword arguments.
 */
data class MetricFlowQueryRequest(
    val metricNames: List<String>,
    val metrics: List<MetricQueryParameter>,
    val groupByNames: List<String>,
    val groupBy: List<GroupByQueryParameter>,
    val whereConstraints: List<WhereFilter>,
    val whereConstraintStrs: List<String>,
    val orderByNames: List<String>,
    val orderBy: List<OrderByQueryParameter>,
    val limit: Int?,
    val timeConstraintStart: java.time.LocalDateTime?,
    val timeConstraintEnd: java.time.LocalDateTime?,
    val minMaxOnly: Boolean,
    val applyGroupBy: Boolean,
) {
    companion object {
        /** Build an empty request. */
        val EMPTY: MetricFlowQueryRequest = MetricFlowQueryRequest(
            metricNames = emptyList(),
            metrics = emptyList(),
            groupByNames = emptyList(),
            groupBy = emptyList(),
            whereConstraints = emptyList(),
            whereConstraintStrs = emptyList(),
            orderByNames = emptyList(),
            orderBy = emptyList(),
            limit = null,
            timeConstraintStart = null,
            timeConstraintEnd = null,
            minMaxOnly = false,
            applyGroupBy = true,
        )
    }
}

/** Wrapper for a TimeRangeConstraint pair — paired with [MetricFlowQueryRequest] when needed. */
data class MetricFlowQueryTimeConstraint(
    val timeRangeConstraint: TimeRangeConstraint?,
)
