package cc.monomer.metricflow.application.engine

import cc.monomer.metricflow.common.errors.SemanticManifestConfigurationError
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.lookup.SemanticModelHelper
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationResults
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidator
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet

/**
 * Top-level facade for the eight non-execution entry points described in
 * [CLAUDE.md](../../../../../../../../../CLAUDE.md) "엔진 인터페이스 표면".
 *
 * Port of `metricflow/engine/metricflow_engine.py::MetricFlowEngine`, minus the
 * SQL-execution methods (`query`, `get_dimension_values`) which are out of
 * scope for the Kotlin port.
 *
 * ## Wave-W10 status
 *
 * | Entry point | Status |
 * |---|---|
 * | `validateManifest` | **Done** — delegates to [SemanticManifestValidator] |
 * | `listMetrics` | **Done** — iterates manifest metrics, includes dimensions via the simple resolver |
 * | `listDimensions` | **Done** — manifest iteration; metric-filtered variant uses the W7c BFS resolver |
 * | `entitiesForMetrics` | **Done** — uses the W7c BFS resolver |
 * | `listGroupBys` | **Done** — dimensions + entities via the W7c BFS resolver |
 * | `listSavedQueries` | **Done** — iterates the manifest's `saved_queries` block |
 * | `explain` | **Deferred** — depends on `DataflowPlanBuilder.buildPlan` body (W11+) |
 * | `explainGetDimensionValues` | **Deferred** — same dependency chain as `explain` |
 *
 * ## Resolver caveats
 *
 * The W7c resolver is the **first-pass BFS reachability** variant — see
 * [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver]
 * for the scope note. It walks every attribute reachable from the metric's
 * semantic graph node and materialises one annotated spec per attribute. The
 * production-grade weighted-DFS resolver (the multi-hop / ambiguity case) is
 * deferred. For most explain-style and entity-filter style cases the simple
 * variant is correct; for unrelated multi-hop manifests the metric-filtered
 * `listDimensions` / `listGroupBys` may return a superset of what Python
 * emits. We surface this as a known caveat rather than a silent stub.
 *
 * The facade keeps construction cheap: every call rebuilds the lookups only on
 * first use of the semantic graph (the graph is `lazy`), so RPCs that only
 * touch the manifest (`validateManifest`, `listSavedQueries`,
 * `listMetrics(includeDimensions=false)`) don't pay graph-construction cost.
 */
class MetricFlowEngine(val semanticManifest: SemanticManifest) {

    /**
     * Manifest-level indexes — sorted name-to-model maps, dimension/entity
     * reverse indexes, metric-by-name lookup, and time-spine sources.
     */
    val semanticManifestLookup: SemanticManifestLookup = SemanticManifestLookup(semanticManifest)

    /**
     * Composition root for the semantic graph + the BFS resolver. Construction
     * is `lazy` inside; we hold the wrapper eagerly so callers can reach it
     * through the engine for advanced flows.
     */
    val semanticManifestGraphLookup: SemanticManifestGraphLookup =
        SemanticManifestGraphLookup(semanticManifestLookup)

    /**
     * Run the canonical 28-rule validator against [semanticManifest].
     *
     * **Never throws** — issues are always returned. Mirrors the Python
     * `validate_semantic_manifest` (non-throwing) entry, as decided in
     * PROGRESS.md Phase 1a insight.
     */
    fun validateManifest(): SemanticManifestValidationResults =
        SemanticManifestValidator.withDefaultRules().validate(semanticManifest)

    /**
     * List the manifest's metrics in `defaultSearchAndSortAttribute` (name) order.
     *
     * When [includeDimensions] is true, the simple BFS resolver is consulted
     * for each metric to materialise the available dimension list. When
     * false the dimension list is empty.
     *
     * Private metrics (`type_params.is_private == true`) are excluded, mirroring
     * Python's `list_metrics`.
     */
    fun listMetrics(includeDimensions: Boolean): List<EngineMetric> {
        val metricLookup = semanticManifestLookup.metricLookup
        val out = mutableListOf<EngineMetric>()
        for (pydanticMetric in metricLookup.getMetrics(metricLookup.metricReferences)) {
            if (pydanticMetric.typeParams.isPrivate == true) continue
            val dimensions = if (includeDimensions) {
                simpleDimensionsForMetrics(listOf(pydanticMetric.name))
            } else {
                emptyList()
            }
            val derivedFrom = metricLookup.getDerivedFromSemanticModels(MetricReference(pydanticMetric.name))
            out.add(EngineMetric.fromManifest(pydanticMetric, dimensions, derivedFrom))
        }
        return out.sortedBy { it.defaultSearchAndSortAttribute }
    }

    /**
     * List all dimensions in the manifest, sorted by `dunder_name`.
     *
     * When [metricNames] is null/empty, iterates every model dimension via
     * [cc.monomer.metricflow.domain.lookup.SemanticModelLookup.getDimensionReferences]
     * (the path Python uses when no metric filter is specified). Otherwise the
     * simple BFS resolver is consulted.
     *
     * Mirrors Python's `list_dimensions(metric_names=None | [...])`.
     */
    fun listDimensions(
        metricNames: List<String>?,
        orderBy: GroupByOrderByAttribute,
    ): List<EngineDimension> {
        val dimensions: List<EngineDimension> = if (!metricNames.isNullOrEmpty()) {
            simpleDimensionsForMetrics(metricNames)
        } else {
            val semanticModelLookup = semanticManifestLookup.semanticModelLookup
            val collected = mutableListOf<EngineDimension>()
            for (dimensionReference in semanticModelLookup.getDimensionReferences()) {
                for (semanticModel in semanticModelLookup.getSemanticModelsForDimension(dimensionReference)) {
                    val pydanticDimension = SemanticModelHelper.getDimensionFromSemanticModel(
                        semanticModel = semanticModel,
                        dimensionReference = dimensionReference,
                    )
                    val primaryEntity = SemanticModelHelper.resolvedPrimaryEntity(semanticModel)
                    collected.add(
                        EngineDimension.fromManifest(
                            dimension = pydanticDimension,
                            entityLinks = listOf(primaryEntity),
                            semanticModelReference = semanticModel.reference,
                        ),
                    )
                }
            }
            collected
        }
        val deduped = dimensions.toSet().toList()
        return deduped.sortedWith(dimensionSorter(orderBy))
    }

    /** List all entities reachable from the specified metric set. */
    fun entitiesForMetrics(metricNames: List<String>): List<EngineEntity> {
        val groupByItemSet = resolveCommonGroupByItems(
            metricNames = metricNames,
            filter = GroupByItemSetFilter.create(
                elementNameAllowlist = null,
                anyPropertiesAllowlist = ENTITY_WITH_ANY_PROPERTIES,
                anyPropertiesDenylist = null,
            ),
        )
        val entities = filterLinkableEntities(groupByItemSet, preferredModels = metricDefiningModels(metricNames))
        return entities.toSet().sortedBy { it.defaultSearchAndSortAttribute }
    }

    /**
     * List the group-by items (dimensions + entities) available for the
     * specified metric set, or all dimensions when no metric filter is given.
     *
     * Mirrors Python's `list_group_bys(metric_names=..., order_by=...)`.
     */
    fun listGroupBys(
        metricNames: List<String>?,
        includeDerivedTimeGranularities: Boolean,
        orderBy: GroupByOrderByAttribute,
    ): GroupByListing {
        if (metricNames.isNullOrEmpty()) {
            // Python: `group_bys = self.list_dimensions()`. Mirror the same behaviour.
            val dims = listDimensions(metricNames = null, orderBy = orderBy)
            return GroupByListing(dimensions = dims, entities = emptyList())
        }
        var withoutAnyOf = SIMPLE_DIMENSIONS_WITHOUT_ANY_PROPERTIES - ENTITY_WITH_ANY_PROPERTIES
        if (includeDerivedTimeGranularities) {
            withoutAnyOf = withoutAnyOf - setOf(GroupByItemProperty.DERIVED_TIME_GRANULARITY)
        }
        val groupByItemSet = resolveCommonGroupByItems(
            metricNames = metricNames,
            filter = GroupByItemSetFilter.create(
                elementNameAllowlist = null,
                anyPropertiesAllowlist = null,
                anyPropertiesDenylist = withoutAnyOf,
            ),
        )
        val preferredModels = metricDefiningModels(metricNames)
        val entities = filterLinkableEntities(groupByItemSet, preferredModels = preferredModels)
        val dimensions = filterSimpleLinkableDimensions(groupByItemSet)
        val sortedDims = dimensions.toSet().sortedWith(dimensionSorter(orderBy))
        val sortedEntities = entities.toSet().sortedBy { it.defaultSearchAndSortAttribute }
        return GroupByListing(dimensions = sortedDims, entities = sortedEntities)
    }

    /** List the manifest's saved queries (sorted by name). */
    fun listSavedQueries(): List<EngineSavedQuery> {
        val all = semanticManifest.savedQueries.map(EngineSavedQuery::fromManifest)
        return all.sortedBy { it.defaultSearchAndSortAttribute }
    }

    /**
     * Render the SQL for a metric query.
     *
     * **W14 wire-up — chain probed, body deferrals propagate.** The full chain is:
     *
     *   user input
     *     → [cc.monomer.metricflow.domain.query.MetricFlowQueryParser.parseAndValidateQuery]   (W14 deferred body — query resolver)
     *     → [cc.monomer.metricflow.domain.dataflow.builder.DataflowPlanBuilder.buildPlan]      (W14 deferred body — metric-evaluation recursion)
     *     → [cc.monomer.metricflow.domain.plan_conversion.DataflowToSqlPlanConverter.convertToSqlPlan] (W13 partial: 15/23 visit bodies; W14 deferred: 8 visit bodies)
     *     → dialect-specific SQL renderer
     *
     * As of W14, the engine wiring **calls** the chain (instead of throwing immediately), so
     * callers receive the first under-the-chain [NotImplementedError] as a concrete deferral
     * with the missing layer named. This is the scaffolding that lets the diff-runner surface
     * per-case UNIMPLEMENTED diagnostics. Once the layers below land their bodies, every
     * corpus case naturally migrates from UNIMPLEMENTED → PASS / FAIL without further wiring.
     *
     * The chain is **not** wrapped in extra try/catch — any [NotImplementedError] raised by a
     * subordinate layer (W14 deferred body) propagates verbatim to the call site, which the
     * diff-runner then categorises as UNIMPLEMENTED. Other exceptions (genuine bugs) surface
     * as ERROR.
     */
    fun explain(request: MetricFlowExplainRequest): MetricFlowExplainResult {
        requireConfiguredTimeSpine(
            metricNames = request.metricNames.orEmpty(),
            groupByNames = request.groupByNames.orEmpty(),
            timeConstraintStart = request.timeConstraintStart,
            timeConstraintEnd = request.timeConstraintEnd,
        )

        // Step 1: parse + validate the request into a MetricFlowQuerySpec.
        val parser = cc.monomer.metricflow.domain.query.MetricFlowQueryParser(
            semanticManifestLookup = semanticManifestLookup,
            graphLookup = semanticManifestGraphLookup,
        )
        val queryResolution = parser.parseAndValidateQuery(
            metricNames = request.metricNames.orEmpty(),
            metrics = emptyList(),
            groupByNames = request.groupByNames.orEmpty(),
            groupBy = emptyList(),
            limit = request.limit,
            timeConstraintStart = parseDateTime(request.timeConstraintStart),
            timeConstraintEnd = parseDateTime(request.timeConstraintEnd),
            whereConstraints = emptyList(),
            whereConstraintStrs = request.whereConstraints.orEmpty(),
            orderByNames = request.orderByNames.orEmpty(),
            orderBy = emptyList(),
            minMaxOnly = request.minMaxOnly,
            applyGroupBy = request.applyGroupBy,
        )
        if (queryResolution.hasErrors) {
            throw IllegalArgumentException(
                "Query failed to resolve: ${queryResolution.inputToIssueSet.mergedIssueSet}",
            )
        }
        val rawQuerySpec = queryResolution.checkedQuerySpec
            ?: throw IllegalStateException("Query resolved without errors but no spec was produced")
        // The W14a resolver doesn't yet propagate the request's time-range bounds into the spec
        // — attach them here so the W14c builder can emit a ConstrainTimeRangeNode.
        val timeRangeConstraint = buildTimeRangeConstraint(
            start = parseDateTime(request.timeConstraintStart),
            end = parseDateTime(request.timeConstraintEnd),
        )
        val querySpec = if (timeRangeConstraint != null) {
            rawQuerySpec.withTimeRangeConstraint(timeRangeConstraint)
        } else {
            rawQuerySpec
        }

        // Step 2-4: build the dataflow plan, convert to SQL plan, render.
        val pipeline = explainPipeline
        val sql = pipeline.renderSql(
            querySpec = querySpec,
            dialect = request.dialect ?: cc.monomer.metricflow.domain.sql.render.SqlEngine.DUCKDB,
            outputSelectionSpecs = null,
        )
        return MetricFlowExplainResult(
            sql = sql,
            querySpec = querySpec,
            queriedSemanticModels = queryResolution.queriedSemanticModels,
            outputSqlTable = null,
        )
    }

    /**
     * Per-engine [ExplainPipeline]. Eager — wires the dataset converter / source-node builder /
     * dataflow-plan builder once per engine instance, mirroring Python's `__init__` cost
     * amortisation. Per-query state is scoped via [SequentialIdGenerator.idNumberSpace].
     */
    private val explainPipeline: ExplainPipeline by lazy { ExplainPipeline(this) }

    /**
     * Render the SQL that would fetch distinct values for a dimension.
     *
     * Port of `MetricFlowEngine.explain_get_dimension_values` — the
     * `MetricFlowQueryType.DIMENSION_VALUES` path through `_create_execution_plan`.
     *
     * Builds a full metric query (so the resolver / planner can apply the same join /
     * constraint logic), but then drops the metric column at the very end by passing an
     * `outputSelectionSpecs` that contains only the queried dimension and time-dimension
     * specs. The metric column gets pruned away by the SQL column-pruner during
     * optimization. The Aggregate's GROUP BY clause survives, which is what gives the
     * caller a deduplicated set of dimension values.
     *
     * Mirrors Python's `MetricFlowEngine._create_execution_plan` lines 590-608 (the
     * `query_type == DIMENSION_VALUES` branch + `output_selection_specs`).
     */
    fun explainGetDimensionValues(request: ExplainGetDimensionValuesRequest): MetricFlowExplainResult {
        requireConfiguredTimeSpine(
            metricNames = request.metricNames,
            groupByNames = listOf(request.getGroupByValues),
            timeConstraintStart = request.timeConstraintStart,
            timeConstraintEnd = request.timeConstraintEnd,
        )

        val parser = cc.monomer.metricflow.domain.query.MetricFlowQueryParser(
            semanticManifestLookup = semanticManifestLookup,
            graphLookup = semanticManifestGraphLookup,
        )
        val queryResolution = parser.parseAndValidateQuery(
            metricNames = request.metricNames,
            metrics = emptyList(),
            groupByNames = listOf(request.getGroupByValues),
            groupBy = emptyList(),
            limit = null,
            timeConstraintStart = parseDateTime(request.timeConstraintStart),
            timeConstraintEnd = parseDateTime(request.timeConstraintEnd),
            whereConstraints = emptyList(),
            whereConstraintStrs = emptyList(),
            orderByNames = emptyList(),
            orderBy = emptyList(),
            minMaxOnly = request.minMaxOnly,
            applyGroupBy = true,
        )
        if (queryResolution.hasErrors) {
            throw IllegalArgumentException(
                "Query failed to resolve: ${queryResolution.inputToIssueSet.mergedIssueSet}",
            )
        }
        val rawQuerySpec = queryResolution.checkedQuerySpec
            ?: throw IllegalStateException("Query resolved without errors but no spec was produced")
        val timeRangeConstraint = buildTimeRangeConstraint(
            start = parseDateTime(request.timeConstraintStart),
            end = parseDateTime(request.timeConstraintEnd),
        )
        val querySpec = if (timeRangeConstraint != null) {
            rawQuerySpec.withTimeRangeConstraint(timeRangeConstraint)
        } else {
            rawQuerySpec
        }
        // Mirror Python's `InvalidQueryException` for entity group-bys in DIMENSION_VALUES.
        if (querySpec.entitySpecs.isNotEmpty()) {
            throw IllegalArgumentException(
                "Querying dimension values for entities is not allowed.",
            )
        }
        // Build the output-selection spec that drops the metric column. Mirrors Python's
        // `InstanceSpecSet(dimension_specs=..., time_dimension_specs=...)` construction at
        // metricflow_engine.py:594-597.
        val outputSelectionSpecs = cc.monomer.metricflow.domain.spec.InstanceSpecSet(
            metricSpecs = emptyList(),
            simpleMetricInputSpecs = emptyList(),
            dimensionSpecs = querySpec.dimensionSpecs,
            entitySpecs = emptyList(),
            timeDimensionSpecs = querySpec.timeDimensionSpecs,
            groupByMetricSpecs = emptyList(),
            metadataSpecs = emptyList(),
        )

        val pipeline = explainPipeline
        val sql = pipeline.renderSql(
            querySpec = querySpec,
            dialect = request.dialect ?: cc.monomer.metricflow.domain.sql.render.SqlEngine.DUCKDB,
            outputSelectionSpecs = outputSelectionSpecs,
        )
        return MetricFlowExplainResult(
            sql = sql,
            querySpec = querySpec,
            queriedSemanticModels = queryResolution.queriedSemanticModels,
            outputSqlTable = null,
        )
    }

    /**
     * Parse an ISO-8601 datetime, falling back to a midnight datetime if the
     * input is date-only.
     *
     * Corpus cases supply time-constraint bounds as `YYYY-MM-DD` (date) **or**
     * `YYYY-MM-DDTHH:MM:SS` (datetime); Python's `MetricFlowQueryRequest`
     * tolerates both via `_normalize_date_string` upstream. We mirror that
     * leniency here.
     */
    private fun parseDateTime(value: String?): java.time.LocalDateTime? =
        value?.let {
            try {
                java.time.LocalDateTime.parse(it)
            } catch (_: java.time.format.DateTimeParseException) {
                java.time.LocalDate.parse(it).atStartOfDay()
            }
        }

    /**
     * Build a [cc.monomer.metricflow.common.time.TimeRangeConstraint] from the explain
     * request's time-constraint bounds. Returns null when either bound is null (no constraint).
     *
     * Port of the inline `TimeRangeConstraint` construction in
     * `MetricFlowEngine._build_query_spec` (Python).
     */
    private fun buildTimeRangeConstraint(
        start: java.time.LocalDateTime?,
        end: java.time.LocalDateTime?,
    ): cc.monomer.metricflow.common.time.TimeRangeConstraint? {
        if (start == null || end == null) return null
        return cc.monomer.metricflow.common.time.TimeRangeConstraint(
            startTime = start,
            endTime = end,
        )
    }

    /**
     * Reject time-dependent requests before resolution when the manifest has no time spine.
     *
     * Lookup construction accepts an empty spine set so atemporal metrics remain usable. This
     * guard preserves the opposite half of that contract: synthetic `metric_time`, explicit time
     * bounds, cumulative / conversion metrics, time offsets, and metrics configured to join to
     * the spine never degrade into fact-table-only SQL.
     */
    private fun requireConfiguredTimeSpine(
        metricNames: List<String>,
        groupByNames: List<String>,
        timeConstraintStart: String?,
        timeConstraintEnd: String?,
    ) {
        if (semanticManifestLookup.timeSpineSources.isNotEmpty()) return

        val usesMetricTime = groupByNames.any(::isMetricTimeQueryName)
        val usesTimeConstraint = timeConstraintStart != null || timeConstraintEnd != null
        val usesTimeDependentMetric = metricNames.any { metricName ->
            val metricReference = MetricReference(metricName)
            metricReference in semanticManifestLookup.metricLookup.metricReferences &&
                metricRequiresTimeSpine(
                    metric = semanticManifestLookup.metricLookup.getMetric(metricReference),
                    visitedMetricNames = linkedSetOf(),
                )
        }

        if (usesMetricTime || usesTimeConstraint || usesTimeDependentMetric) {
            throw SemanticManifestConfigurationError(
                "This query requires a configured time spine, but the manifest has none.",
            )
        }
    }

    private fun metricRequiresTimeSpine(
        metric: Metric,
        visitedMetricNames: MutableSet<String>,
    ): Boolean {
        if (!visitedMetricNames.add(metric.name)) return false
        if (metric.type == MetricType.CUMULATIVE || metric.type == MetricType.CONVERSION) return true
        if (
            metric.type == MetricType.SIMPLE &&
            (
                metric.typeParams.joinToTimespine ||
                    metric.typeParams.measure?.joinToTimespine == true ||
                    metric.inputMeasures.any { it.joinToTimespine }
                )
        ) {
            return true
        }

        return metric.inputMetrics.any { metricInput ->
            metricInput.offsetWindow != null ||
                metricInput.offsetToGrain != null ||
                metricRequiresTimeSpine(
                    metric = semanticManifestLookup.metricLookup.getMetric(metricInput.asReference),
                    visitedMetricNames = visitedMetricNames,
                )
        }
    }

    private fun isMetricTimeQueryName(queryName: String): Boolean =
        queryName.substringBefore("__").equals(METRIC_TIME_ELEMENT_NAME, ignoreCase = true)

    // --- Helpers -----------------------------------------------------------

    /**
     * Port of `MetricFlowEngine.simple_dimensions_for_metrics`.
     *
     * Uses the W7c BFS resolver — `metric_lookup.get_common_group_by_items`
     * isn't yet wired through, so we call directly into the graph resolver and
     * apply the same filter Python builds.
     */
    private fun simpleDimensionsForMetrics(metricNames: List<String>): List<EngineDimension> {
        checkMetricNames(metricNames)
        val groupByItemSet = resolveCommonGroupByItems(
            metricNames = metricNames,
            filter = GroupByItemSetFilter.create(
                elementNameAllowlist = null,
                anyPropertiesAllowlist = null,
                anyPropertiesDenylist = SIMPLE_DIMENSIONS_WITHOUT_ANY_PROPERTIES,
            ),
        )
        return filterSimpleLinkableDimensions(groupByItemSet).toSet().sortedBy { it.defaultSearchAndSortAttribute }
    }

    private fun checkMetricNames(metricNames: Iterable<String>) {
        val metricLookup = semanticManifestLookup.metricLookup
        val unknown = metricNames.filter { MetricReference(it) !in metricLookup.metricReferences }
        if (unknown.isNotEmpty()) {
            throw IllegalArgumentException("Unknown metric names: $unknown")
        }
    }

    /**
     * Combined resolver: for one metric we walk its semantic-graph node, for N
     * we intersect each metric's result set (mirrors Python's `intersection`).
     * The empty allowlist case falls through to "no metric" (every model).
     */
    private fun resolveCommonGroupByItems(
        metricNames: List<String>,
        filter: GroupByItemSetFilter,
    ): GroupByItemSet {
        val resolver = semanticManifestGraphLookup.groupByItemSetResolver
        if (metricNames.isEmpty()) {
            return resolver.resolveAvailableItemsForNoMetricsInQuery().filter(filter)
        }
        val sets = metricNames.map { name ->
            resolver.resolveAvailableItemsForMetric(MetricReference(name))
        }
        val combined = if (sets.size == 1) sets[0] else sets.first().intersection(*sets.drop(1).toTypedArray())
        return combined.filter(filter)
    }

    /**
     * Return the semantic-model reference that contains the metric's
     * aggregation source. Used to prefer "local" origin when emitting entities
     * and dimensions whose annotated-spec origin list is over-populated by
     * the simple BFS resolver.
     */
    private fun metricDefiningModels(metricNames: List<String>): List<cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference> {
        val out = mutableListOf<cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference>()
        for (name in metricNames) {
            try {
                out.addAll(semanticManifestLookup.metricLookup.getDerivedFromSemanticModels(MetricReference(name)))
            } catch (_: Throwable) {
                // unknown metric: ignored — the caller catches via checkMetricNames.
            }
        }
        return out.distinct()
    }

    /**
     * Port of `MetricFlowEngine._filter_simple_linkable_dimensions`.
     *
     * Drops DATE_PART specs (simple dimensions hide extracts), maps METRIC_TIME
     * to a synthetic dimension (no semantic model), maps DIMENSION /
     * TIME_DIMENSION to one [EngineDimension] per origin semantic model.
     */
    private fun filterSimpleLinkableDimensions(set: GroupByItemSet): List<EngineDimension> {
        val out = mutableListOf<EngineDimension>()
        for (annotated in set.annotatedSpecs) {
            val properties = annotated.propertySet
            when (annotated.elementType) {
                LinkableElementType.TIME_DIMENSION -> {
                    if (GroupByItemProperty.DATE_PART in properties) continue
                    if (GroupByItemProperty.METRIC_TIME in properties) {
                        out.add(buildMetricTimeDimension(annotated))
                    } else {
                        out.addAll(createDimensionsFromSpec(annotated))
                    }
                }
                LinkableElementType.DIMENSION -> out.addAll(createDimensionsFromSpec(annotated))
                LinkableElementType.ENTITY, LinkableElementType.METRIC -> Unit
            }
        }
        return out
    }

    /**
     * Port of `MetricFlowEngine._filter_linkable_entities`.
     *
     * Python uses `mf_first_item(annotated_spec.origin_model_ids)` to pick the
     * canonical origin model for each ENTITY-typed annotated spec, then dedupes
     * the resulting [EngineEntity] objects by `(name, semantic_model_name)`
     * equality. Now that the W11 DFS resolver populates `originModelIds` with
     * the path-final model (matching Python's
     * `recipe.joined_model_ids[-1]`), the heuristic that W10 used (a
     * `preferredModels` override) is no longer needed and would in fact emit
     * the wrong origin for multi-metric queries — see the `bookings`+`views`
     * regression that motivated this W11 simplification.
     *
     * The parameter [preferredModels] is retained for call-site stability but
     * is no longer consulted; callers may pass `emptyList()` going forward.
     */
    private fun filterLinkableEntities(
        set: GroupByItemSet,
        @Suppress("UNUSED_PARAMETER")
        preferredModels: List<cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference>,
    ): List<EngineEntity> {
        val out = mutableListOf<EngineEntity>()
        val semanticModelLookup = semanticManifestLookup.semanticModelLookup
        // Dedup key matches Python's `Entity` equality (name + semantic_model_name).
        val seenKeys = mutableSetOf<Pair<String, String>>()
        for (annotated in set.annotatedSpecs) {
            if (annotated.elementType != LinkableElementType.ENTITY) continue
            val originReference = annotated.originSemanticModelReferences.firstOrNull() ?: continue
            val semanticModel = semanticModelLookup.getByReference(originReference) ?: continue
            val entityName = annotated.spec.elementName
            val matched = semanticModel.entities.firstOrNull { it.reference.elementName == entityName }
                ?: continue
            val key = entityName to semanticModel.reference.semanticModelName
            if (key in seenKeys) continue
            seenKeys.add(key)
            out.add(
                EngineEntity.fromManifest(
                    entity = matched,
                    semanticModelReference = semanticModel.reference,
                ),
            )
        }
        return out
    }

    /** Port of `MetricFlowEngine._build_metric_time_dimension`. */
    private fun buildMetricTimeDimension(annotated: AnnotatedSpec): EngineDimension {
        val name = cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
        val timeGrain = annotated.timeGrain
        val grainName = timeGrain?.name
        val dunder = cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName(
            entityLinkNames = emptyList(),
            elementName = name,
            timeGranularityName = grainName,
            datePart = null,
            metricSubqueryEntityLinkNames = null,
        ).dunderName
        val typeParams = if (timeGrain != null) {
            cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams(
                timeGranularity = timeGrain.baseGranularity,
                validityParams = null,
            )
        } else {
            null
        }
        return EngineDimension(
            name = name,
            dunderName = dunder,
            description = "Event time for metrics.",
            type = cc.monomer.metricflow.domain.manifest.model.enums.DimensionType.TIME,
            entityLinks = emptyList(),
            typeParams = typeParams,
            metadata = null,
            semanticModelReference = null,
            config = null,
            isPartition = false,
            expr = null,
            label = null,
        )
    }

    /**
     * Port of `MetricFlowEngine._create_dimension_from_spec`.
     *
     * Now that the W11 DFS resolver provides the correct
     * `originSemanticModelReferences` (one entry per resolved path, naming the
     * path-final semantic model), each origin produces one
     * [EngineDimension]. The entity-link prefix comes directly from the
     * resolved spec, which is what Python emits.
     *
     * The W10 fallback to the model's primary entity is no longer needed: with
     * a path-aware resolver the DIMENSION recipe always has at least one
     * entity link (enforced by [PathEnumerator]'s entity-link constraint).
     * Specs that somehow arrive with an empty entity-link list — only the
     * metric-time entry — are filtered out by the caller before this method
     * runs.
     */
    private fun createDimensionsFromSpec(annotated: AnnotatedSpec): List<EngineDimension> {
        val semanticModelLookup = semanticManifestLookup.semanticModelLookup
        val out = mutableListOf<EngineDimension>()
        for (originReference in annotated.originSemanticModelReferences) {
            val semanticModel = semanticModelLookup.getByReference(originReference) ?: continue
            val pydanticDimension = try {
                SemanticModelHelper.getDimensionFromSemanticModel(
                    semanticModel = semanticModel,
                    dimensionReference = annotated.spec.reference,
                )
            } catch (_: IllegalArgumentException) {
                continue
            }
            out.add(
                EngineDimension.fromManifest(
                    dimension = pydanticDimension,
                    entityLinks = annotated.spec.entityLinks,
                    semanticModelReference = originReference,
                ),
            )
        }
        return out
    }

    /**
     * Sort comparator for [EngineDimension] lists. Mirrors the Python
     * `sort_dimensions` helper inside `list_dimensions` / `list_group_bys`.
     */
    private fun dimensionSorter(orderBy: GroupByOrderByAttribute): Comparator<EngineDimension> =
        when (orderBy) {
            GroupByOrderByAttribute.DUNDER_NAME -> compareBy { it.dunderName }
            GroupByOrderByAttribute.SEMANTIC_MODEL_NAME -> compareBy<EngineDimension> {
                it.semanticModelReference?.semanticModelName.orEmpty()
            }.thenBy { it.dunderName }
        }

    companion object {
        /** Properties hidden from "simple" dimension queries — see Python's constant of the same name. */
        val SIMPLE_DIMENSIONS_WITHOUT_ANY_PROPERTIES: Set<GroupByItemProperty> = setOf(
            GroupByItemProperty.ENTITY,
            GroupByItemProperty.DERIVED_TIME_GRANULARITY,
            GroupByItemProperty.DATE_PART,
            GroupByItemProperty.LOCAL_LINKED,
            GroupByItemProperty.METRIC,
        )

        /** Properties allowlist for entity queries — see Python's `ENTITY_WITH_ANY_PROPERTIES`. */
        val ENTITY_WITH_ANY_PROPERTIES: Set<GroupByItemProperty> = setOf(GroupByItemProperty.ENTITY)
    }
}

/**
 * Combined result of [MetricFlowEngine.listGroupBys] — dimensions and entities
 * are returned together because the gRPC contract surfaces them in one
 * response message.
 *
 * Python returns a flat `list[Entity | Dimension]`; we split because Kotlin
 * does not have a sealed-union of the two engine DTOs. The combined sort order
 * is recoverable by callers (`entities.sortedBy(name)` + `dimensions.sortedBy(dunder)`),
 * which Python does too via `sorted(set(group_bys), key=sort_group_bys)`.
 */
data class GroupByListing(
    val dimensions: List<EngineDimension>,
    val entities: List<EngineEntity>,
)

/**
 * Result payload of [MetricFlowEngine.explain].
 *
 * Port of `metricflow.engine.metricflow_engine.MetricFlowExplainResult`. The Python record
 * carries an `ExecutionPlan` (the task we **don't** port — see CLAUDE.md scope clause); the
 * Kotlin equivalent collapses that to a single `sql` string plus optional `outputSqlTable`
 * since SQL execution is out of scope. The `queriedSemanticModels` field mirrors what
 * Python's `MetricFlowEngine.explain` exposes via `query_spec.queried_semantic_models`.
 *
 * **Wave-W14 status — unreachable in practice.** The engine's explain method throws
 * [NotImplementedError] before constructing this result. The type exists so call-site code
 * compiles and so future waves can `return MetricFlowExplainResult(...)` without changing
 * any signatures.
 */
data class MetricFlowExplainResult(
    val sql: String,
    val querySpec: cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec,
    val queriedSemanticModels: List<cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference>,
    val outputSqlTable: cc.monomer.metricflow.domain.spec.bind.SqlTable?,
)

/**
 * Request payload for [MetricFlowEngine.explain] — mirrors `MetricFlowQueryRequest`.
 *
 * The Kotlin port adds an optional [dialect] field that Python's `MetricFlowEngine` doesn't
 * carry (Python derives it from the engine's bound `SqlClient`). We surface it here because
 * the Kotlin engine has no SqlClient and the corpus diff exercises every dialect explicitly.
 * Null is treated as DUCKDB.
 */
data class MetricFlowExplainRequest(
    val metricNames: List<String>?,
    val groupByNames: List<String>?,
    val whereConstraints: List<String>?,
    val orderByNames: List<String>?,
    val limit: Int?,
    val timeConstraintStart: String?,
    val timeConstraintEnd: String?,
    val savedQueryName: String?,
    val minMaxOnly: Boolean,
    val applyGroupBy: Boolean,
    val orderOutputColumnsByInputOrder: Boolean,
    val dialect: cc.monomer.metricflow.domain.sql.render.SqlEngine?,
)

/** Request payload for [MetricFlowEngine.explainGetDimensionValues]. */
data class ExplainGetDimensionValuesRequest(
    val metricNames: List<String>,
    val getGroupByValues: String,
    val timeConstraintStart: String?,
    val timeConstraintEnd: String?,
    val minMaxOnly: Boolean,
    val dialect: cc.monomer.metricflow.domain.sql.render.SqlEngine?,
)
