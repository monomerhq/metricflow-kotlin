package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.lookup.SemanticModelDerivation
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.query.filter.DefaultWhereFilterPatternFactory
import cc.monomer.metricflow.domain.query.filter.WhereFilterPatternFactory
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.GroupByItemResolutionDag
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.GroupByItemResolutionDagBuilder
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.ComplexMetricGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.QueryGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.input.ResolverInputForApplyGroupBy
import cc.monomer.metricflow.domain.query.input.ResolverInputForGroupByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForLimit
import cc.monomer.metricflow.domain.query.input.ResolverInputForMetric
import cc.monomer.metricflow.domain.query.input.ResolverInputForMinMaxOnly
import cc.monomer.metricflow.domain.query.input.ResolverInputForOrderByItem
import cc.monomer.metricflow.domain.query.input.ResolverInputForQuery
import cc.monomer.metricflow.domain.query.input.ResolverInputForQueryLevelWhereFilterIntersection
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidApplyGroupByIssue
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidLimitIssue
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidMetricIssue
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidMinMaxOnlyIssue
import cc.monomer.metricflow.domain.query.issue.parsing.InvalidOrderByItemIssue
import cc.monomer.metricflow.domain.query.issue.parsing.NoMetricOrGroupByIssue
import cc.monomer.metricflow.domain.query.resolution.FilterSpecResolutionLookUp
import cc.monomer.metricflow.domain.query.resolution.InputToIssueSetMapping
import cc.monomer.metricflow.domain.query.resolution.InputToIssueSetMappingItem
import cc.monomer.metricflow.domain.query.resolution.MetricFlowQueryResolution
import cc.monomer.metricflow.domain.query.validation.ResolveGroupByItemsResult
import cc.monomer.metricflow.domain.query.validation.ResolveMetricsResult
import cc.monomer.metricflow.domain.query.validation.DuplicateMetricValidationRule
import cc.monomer.metricflow.domain.query.validation.MetricTimeQueryValidationRule
import cc.monomer.metricflow.domain.query.validation.UniqueOutputColumnValidationRule
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.GroupByItemSet
import cc.monomer.metricflow.domain.spec.InputSpecOrder
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableSpecSet
import cc.monomer.metricflow.domain.spec.MetricFlowQuerySpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.OrderBySpec

/**
 * Resolves inputs to a query (metrics, group-by items, order-by, where filters,
 * limit, min/max, apply-group-by) into a [MetricFlowQuerySpec] that
 * downstream layers (the dataflow planner, the engine facade) consume.
 *
 * Port of `metricflow_semantics.query.query_resolver.MetricFlowQueryResolver`.
 *
 * ## Pipeline (mirrors Python's `_resolve_query`)
 *
 * 1. [resolveMetricInputs] — match each metric input's [cc.monomer.metricflow.domain.spec.pattern.SpecPattern]
 *    against the manifest's known metric references. Unknown metrics generate
 *    [InvalidMetricIssue]s.
 * 2. Build the resolution-DAG query path so subsequent issues can anchor at
 *    the query node.
 * 3. [resolveHasMetricOrGroupByInputs] / [resolveLimitInput] /
 *    [resolveMinMaxOnlyInput] / [resolveApplyGroupByInput] — surface-level
 *    validation of the user's flags.
 * 4. **Early exit.** If anything above produced issues, return without
 *    resolving anything else (matches Python's behaviour — later issues would
 *    be misleading).
 * 5. [resolveGroupByItemsResult] — resolve each group-by-item input via the
 *    W11 [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver]:
 *    compute the intersection of available items across queried metrics, then
 *    filter by the input's spec pattern. The DAG built in step 2 is also
 *    constructed here.
 * 6. [resolveOrderBy] — match every order-by input to either a metric spec or
 *    a resolved group-by spec.
 * 7. [buildFilterSpecLookup] — currently delegates to the W8 placeholder
 *    factory that returns an empty lookup (the Jinja sandbox is deferred).
 * 8. Run the W8 post-resolution validation rules
 *    ([MetricTimeQueryValidationRule], [DuplicateMetricValidationRule],
 *    [UniqueOutputColumnValidationRule]).
 * 9. Compose [MetricFlowQuerySpec] with all resolved specs +
 *    [InputSpecOrder] capturing user-supplied ordering.
 *
 * ## Simplifications vs. Python
 *
 * Python's resolver runs the group-by-item resolver through
 * `_PushDownGroupByItemCandidatesVisitor`, which walks the resolution DAG
 * bottom-up intersecting candidate sets per node. Path tracking allows
 * fine-grained issue attribution (e.g. "this dimension is unreachable through
 * `derived_metric → input metric A` specifically"). The Kotlin port instead
 * intersects per-metric `GroupByItemSet`s directly via the W11 resolver and
 * does pattern matching once on the intersection.
 *
 * The behavioural delta is bounded:
 *
 * - For SIMPLE metric queries (the W14b/c target scope), the per-metric
 *   intersection equals the push-down result. PASS-equivalent.
 * - For derived/cumulative/ratio metrics, push-down's per-input-metric
 *   handling can mask group-by items that aren't reachable from *all* input
 *   metrics. The intersection used here is equivalent at the *result* level
 *   because the W11 resolver already does the per-component intersection
 *   inside `resolveAvailableItemsForMetric` (the trie-level
 *   `intersectionMergeCommon`).
 * - Path-anchored ambiguity issues use the query node rather than the
 *   deeper resolution path. Error messages lose some context but stay
 *   accurate.
 *
 * Full push-down semantics are deferred to W14c when the dataflow builder
 * needs them.
 *
 * ## Deferred sub-systems used as placeholders
 *
 * - **Filter-spec resolution** — uses the W8 [WhereFilterPatternFactory]
 *   plumbing but the actual where-filter Jinja parse is in
 *   [cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory]'s
 *   deferred branch. Today the resolver returns
 *   [FilterSpecResolutionLookUp.EMPTY]; non-empty filter intersections in the
 *   query input fall through to the W14b builder which then receives the
 *   filter intersection on the query spec.
 *
 * - **Order-by alias support / suggestions** — Python's `OrderByHelper`
 *   builds per-alias buckets so order-by inputs that specify an `alias` only
 *   match specs with that alias. The Kotlin port matches on the full spec
 *   list; alias-bound order-by inputs are a Phase 5 polish item (not exercised
 *   by the W14 corpus cases).
 */
class MetricFlowQueryResolver(
    private val manifestLookup: SemanticManifestLookup,
    private val graphLookup: SemanticManifestGraphLookup,
    private val whereFilterPatternFactory: WhereFilterPatternFactory,
) {

    /**
     * Convenience constructor that defaults the where-filter pattern factory.
     *
     * Mirrors how [MetricFlowQueryParser] keeps the call-site API stable.
     */
    constructor(
        manifestLookup: SemanticManifestLookup,
        graphLookup: SemanticManifestGraphLookup,
    ) : this(
        manifestLookup = manifestLookup,
        graphLookup = graphLookup,
        whereFilterPatternFactory = DefaultWhereFilterPatternFactory(),
    )

    /**
     * Entry point: resolve [resolverInputForQuery] to a
     * [MetricFlowQueryResolution].
     *
     * On any input issue the result carries `querySpec = null` and the
     * accumulated issue mapping. Callers should branch on
     * [MetricFlowQueryResolution.hasErrors] before consuming
     * `checkedQuerySpec`.
     */
    fun resolveQuery(resolverInputForQuery: ResolverInputForQuery): MetricFlowQueryResolution {
        val metricInputs = resolverInputForQuery.metricInputs
        val groupByItemInputs = resolverInputForQuery.groupByItemInputs
        val orderByItemInputs = resolverInputForQuery.orderByItemInputs
        val limitInput = resolverInputForQuery.limitInput
        val queryLevelFilterInput = resolverInputForQuery.filterInput
        val minMaxOnlyInput = resolverInputForQuery.minMaxOnly
        val applyGroupByInput = resolverInputForQuery.applyGroupBy

        val mappingsToMerge = mutableListOf<InputToIssueSetMapping>()

        // Step 1: resolve metric inputs.
        val resolveMetricsResult = resolveMetricInputs(
            metricInputs = metricInputs,
            queryResolutionPath = MetricFlowQueryResolutionPath.EMPTY,
        )
        mappingsToMerge.add(resolveMetricsResult.mapping)
        val metricSpecs = resolveMetricsResult.metricSpecs

        // Step 2: anchor a query-node resolution path for subsequent issues.
        val queryResolutionPath = MetricFlowQueryResolutionPath.fromPathItem(
            QueryGroupByItemResolutionNode.create(
                parentNodes = emptyList(),
                metricsInQuery = metricSpecs.map { it.reference },
                whereFilterIntersection = queryLevelFilterInput.whereFilterIntersection,
            ),
        )

        // Step 3: surface-level validation.
        mappingsToMerge.add(
            resolveHasMetricOrGroupByInputs(
                resolverInputForQuery = resolverInputForQuery,
                queryResolutionPath = queryResolutionPath,
            ),
        )
        mappingsToMerge.add(
            resolveLimitInput(limitInput = limitInput, queryResolutionPath = queryResolutionPath),
        )
        mappingsToMerge.add(
            resolveMinMaxOnlyInput(
                minMaxOnlyInput = minMaxOnlyInput,
                queryResolutionPath = queryResolutionPath,
                metricInputs = metricInputs,
                groupByItemInputs = groupByItemInputs,
                orderByItemInputs = orderByItemInputs,
                limitInput = limitInput,
            ),
        )
        mappingsToMerge.add(
            resolveApplyGroupByInput(
                applyGroupByInput = applyGroupByInput,
                metricInputs = metricInputs,
                queryResolutionPath = queryResolutionPath,
            ),
        )

        // Step 4: early stop on input-level issues — subsequent issues would be misleading.
        val issueSetMappingSoFar = mergeIterable(mappingsToMerge)
        if (issueSetMappingSoFar.hasIssues) {
            return MetricFlowQueryResolution(
                querySpec = null,
                resolutionDag = null,
                filterSpecLookup = FilterSpecResolutionLookUp.EMPTY,
                inputToIssueSet = issueSetMappingSoFar,
                queriedSemanticModels = emptyList(),
            )
        }

        // Step 5: resolve group-by items + build the DAG.
        val resolveGroupByItemResult = resolveGroupByItemsResult(
            metricReferences = metricSpecs.map { it.reference },
            groupByItemInputs = groupByItemInputs,
            filterInput = queryLevelFilterInput,
        )
        val resolutionDag = resolveGroupByItemResult.resolutionDag
        val groupByItemSpecs = resolveGroupByItemResult.groupByItemSpecs
        mappingsToMerge.add(resolveGroupByItemResult.mapping)

        // Step 6: resolve order-by.
        val resolveOrderByResult = resolveOrderBy(
            resolverInputsForOrderByItems = orderByItemInputs,
            metricSpecs = metricSpecs,
            groupByItemSpecs = groupByItemSpecs,
            queryResolutionPath = queryResolutionPath,
        )
        val orderBySpecs = resolveOrderByResult.orderBySpecs
        if (resolveOrderByResult.mapping.hasIssues) {
            mappingsToMerge.add(resolveOrderByResult.mapping)
        }

        // Step 7: where-filter spec lookup. The W8 factory is structural-only
        // (Jinja sandbox deferred); we wire it for forward compatibility but
        // treat its return as an opaque blob.
        val filterSpecLookup = buildFilterSpecLookup(resolutionDag)
        // Surface any non-parsable / errored filter resolutions.
        for (resolution in filterSpecLookup.specResolutions) {
            if (resolution.issueSet.hasIssues) {
                mappingsToMerge.add(
                    InputToIssueSetMapping.fromOneItem(
                        resolverInput = cc.monomer.metricflow.domain.query.input
                            .ResolverInputForWhereFilterIntersection(
                                whereFilterIntersection = resolution.whereFilterIntersection,
                                filterResolutionPath = resolution.filterLocationPath,
                                objectBuilderStr = resolution.objectBuilderStr,
                            ),
                        issueSet = resolution.issueSet,
                    ),
                )
            }
        }
        for (nonParsable in filterSpecLookup.nonParsableResolutions) {
            mappingsToMerge.add(
                InputToIssueSetMapping.fromOneItem(
                    resolverInput = cc.monomer.metricflow.domain.query.input
                        .ResolverInputForWhereFilterIntersection(
                            whereFilterIntersection = nonParsable.whereFilterIntersection,
                            filterResolutionPath = nonParsable.filterLocationPath,
                            objectBuilderStr = null,
                        ),
                    issueSet = nonParsable.issueSet,
                ),
            )
        }

        // Re-check after filter resolution.
        val afterFilter = mergeIterable(mappingsToMerge)
        if (afterFilter.hasIssues) {
            return MetricFlowQueryResolution(
                querySpec = null,
                resolutionDag = resolutionDag,
                filterSpecLookup = filterSpecLookup,
                inputToIssueSet = afterFilter,
                queriedSemanticModels = emptyList(),
            )
        }

        // Step 8: post-resolution validation rules.
        val resolveMetricResultForRules = ResolveMetricsResult(metricSpecs = metricSpecs)
        val resolveGroupByItemResultForRules = ResolveGroupByItemsResult(
            groupByItemSpecs = groupByItemSpecs,
        )
        val queryLevelIssueSet = runValidationRules(
            resolutionDag = resolutionDag,
            resolverInputForQuery = resolverInputForQuery,
            resolveMetricResult = resolveMetricResultForRules,
            resolveGroupByItemResult = resolveGroupByItemResultForRules,
        )
        if (queryLevelIssueSet.hasIssues) {
            mappingsToMerge.add(
                InputToIssueSetMapping(
                    items = listOf(
                        InputToIssueSetMappingItem(
                            resolverInput = resolverInputForQuery,
                            issueSet = queryLevelIssueSet,
                        ),
                    ),
                ),
            )
        }

        val finalIssueSetMapping = mergeIterable(mappingsToMerge)
        if (finalIssueSetMapping.hasIssues) {
            return MetricFlowQueryResolution(
                querySpec = null,
                resolutionDag = resolutionDag,
                filterSpecLookup = filterSpecLookup,
                inputToIssueSet = finalIssueSetMapping,
                queriedSemanticModels = emptyList(),
            )
        }

        // Step 9: compose the final query spec.
        val linkableSpecSet = LinkableSpecSet.createFromSpecs(groupByItemSpecs)
        val queriedSemanticModels = collectQueriedSemanticModels(
            resolutionDag = resolutionDag,
            groupByItemSet = resolveGroupByItemResult.linkableElementSet,
            filterSpecLookup = filterSpecLookup,
        )

        return MetricFlowQueryResolution(
            querySpec = MetricFlowQuerySpec(
                metricSpecs = metricSpecs,
                dimensionSpecs = linkableSpecSet.dimensionSpecs,
                entitySpecs = linkableSpecSet.entitySpecs,
                timeDimensionSpecs = linkableSpecSet.timeDimensionSpecs,
                groupByMetricSpecs = linkableSpecSet.groupByMetricSpecs,
                orderBySpecs = orderBySpecs,
                timeRangeConstraint = null,
                limit = limitInput.limit,
                filterIntersection = queryLevelFilterInput.whereFilterIntersection,
                filterSpecResolutionLookup = filterSpecLookup,
                minMaxOnly = minMaxOnlyInput.minMaxOnly,
                applyGroupBy = applyGroupByInput.applyGroupBy,
                inputSpecOrder = InputSpecOrder(
                    groupByItemSpecs = groupByItemSpecs,
                    metricSpecs = metricSpecs,
                ),
            ),
            resolutionDag = resolutionDag,
            filterSpecLookup = filterSpecLookup,
            inputToIssueSet = finalIssueSetMapping,
            queriedSemanticModels = queriedSemanticModels,
        )
    }

    // --- Step 1: metric inputs --------------------------------------------

    /** Result of [resolveMetricInputs]. */
    private data class MetricInputResolution(
        val metricSpecs: List<MetricSpec>,
        val mapping: InputToIssueSetMapping,
    )

    /**
     * Match every metric input against the manifest's metric references.
     *
     * Mirrors `_resolve_metric_inputs`. Order is preserved.
     */
    private fun resolveMetricInputs(
        metricInputs: List<ResolverInputForMetric>,
        queryResolutionPath: MetricFlowQueryResolutionPath,
    ): MetricInputResolution {
        val availableMetricSpecs: List<MetricSpec> = manifestLookup.metricLookup.metricReferences
            .map { MetricSpec.fromReference(it) }

        val metricSpecs = mutableListOf<MetricSpec>()
        val mappingItems = mutableListOf<InputToIssueSetMappingItem>()

        for (metricInput in metricInputs) {
            val matchingSpecs: List<MetricSpec> = metricInput.specPattern
                .match(availableMetricSpecs)
                .filterIsInstance<MetricSpec>()
            if (matchingSpecs.size == 1) {
                val matched = matchingSpecs[0]
                val withAlias = metricInput.alias?.let { matched.withAlias(it) } ?: matched
                metricSpecs.add(withAlias)
            } else {
                mappingItems.add(
                    InputToIssueSetMappingItem(
                        resolverInput = metricInput,
                        issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                            InvalidMetricIssue.fromParameters(
                                metricSuggestions = emptyList(),
                                queryResolutionPath = queryResolutionPath,
                            ),
                        ),
                    ),
                )
            }
        }

        return MetricInputResolution(
            metricSpecs = metricSpecs,
            mapping = InputToIssueSetMapping(items = mappingItems),
        )
    }

    // --- Step 3 helpers: shallow input validation --------------------------

    private fun resolveHasMetricOrGroupByInputs(
        resolverInputForQuery: ResolverInputForQuery,
        queryResolutionPath: MetricFlowQueryResolutionPath,
    ): InputToIssueSetMapping {
        if (resolverInputForQuery.metricInputs.isEmpty() &&
            resolverInputForQuery.groupByItemInputs.isEmpty()
        ) {
            return InputToIssueSetMapping.fromOneItem(
                resolverInput = resolverInputForQuery,
                issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                    NoMetricOrGroupByIssue.fromParameters(
                        queryResolutionPath = queryResolutionPath,
                    ),
                ),
            )
        }
        return InputToIssueSetMapping.EMPTY
    }

    private fun resolveLimitInput(
        limitInput: ResolverInputForLimit,
        queryResolutionPath: MetricFlowQueryResolutionPath,
    ): InputToIssueSetMapping {
        val limit = limitInput.limit
        if (limit != null && limit < 0) {
            return InputToIssueSetMapping.fromOneItem(
                resolverInput = limitInput,
                issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                    InvalidLimitIssue.fromParameters(
                        limit = limit,
                        queryResolutionPath = queryResolutionPath,
                    ),
                ),
            )
        }
        return InputToIssueSetMapping.EMPTY
    }

    private fun resolveMinMaxOnlyInput(
        minMaxOnlyInput: ResolverInputForMinMaxOnly,
        queryResolutionPath: MetricFlowQueryResolutionPath,
        metricInputs: List<ResolverInputForMetric>,
        groupByItemInputs: List<ResolverInputForGroupByItem>,
        orderByItemInputs: List<ResolverInputForOrderByItem>,
        limitInput: ResolverInputForLimit,
    ): InputToIssueSetMapping {
        if (!minMaxOnlyInput.minMaxOnly) return InputToIssueSetMapping.EMPTY
        val invalid = metricInputs.isNotEmpty() ||
            orderByItemInputs.isNotEmpty() ||
            limitInput.limit != null ||
            groupByItemInputs.size != 1
        if (!invalid) return InputToIssueSetMapping.EMPTY
        return InputToIssueSetMapping.fromOneItem(
            resolverInput = minMaxOnlyInput,
            issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                InvalidMinMaxOnlyIssue.fromParameters(
                    queryResolutionPath = queryResolutionPath,
                ),
            ),
        )
    }

    private fun resolveApplyGroupByInput(
        applyGroupByInput: ResolverInputForApplyGroupBy,
        metricInputs: List<ResolverInputForMetric>,
        queryResolutionPath: MetricFlowQueryResolutionPath,
    ): InputToIssueSetMapping {
        if (metricInputs.isNotEmpty() && !applyGroupByInput.applyGroupBy) {
            return InputToIssueSetMapping.fromOneItem(
                resolverInput = applyGroupByInput,
                issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                    InvalidApplyGroupByIssue.fromParameters(
                        queryResolutionPath = queryResolutionPath,
                    ),
                ),
            )
        }
        return InputToIssueSetMapping.EMPTY
    }

    // --- Step 5: group-by items + DAG -------------------------------------

    /** Result of [resolveGroupByItemsResult]. */
    private data class GroupByItemResolutionResult(
        val resolutionDag: GroupByItemResolutionDag,
        val groupByItemSpecs: List<LinkableInstanceSpec>,
        val mapping: InputToIssueSetMapping,
        val linkableElementSet: GroupByItemSet,
    )

    /**
     * Resolve group-by-item inputs to concrete linkable specs using the W11
     * [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver].
     *
     * Steps:
     *
     * 1. Build the resolution DAG (this is the same DAG the engine would have
     *    built; we materialise it for downstream consumers).
     * 2. Compute the intersection [GroupByItemSet] across queried metrics
     *    (mirrors the Python push-down's per-metric intersection — the W11
     *    resolver already does the inner intersection for complex metrics).
     * 3. For each input, filter the intersection by the input's spec pattern;
     *    if exactly one spec matches, take it (with alias applied).
     * 4. The aggregate linkable element set is the **union** of every matched
     *    input's set (mirrors Python's `linkable_element_sets[0].union(*...)`).
     */
    private fun resolveGroupByItemsResult(
        metricReferences: List<MetricReference>,
        groupByItemInputs: List<ResolverInputForGroupByItem>,
        filterInput: ResolverInputForQueryLevelWhereFilterIntersection,
    ): GroupByItemResolutionResult {
        val resolutionDag = GroupByItemResolutionDagBuilder(manifestLookup).build(
            metricReferences = metricReferences,
            whereFilterIntersection = filterInput.whereFilterIntersection,
        )

        val availableSet: GroupByItemSet = computeAvailableGroupByItemSet(metricReferences)

        val mappingItems = mutableListOf<InputToIssueSetMappingItem>()
        val matchedSpecs = mutableListOf<LinkableInstanceSpec>()
        val matchedSets = mutableListOf<GroupByItemSet>()

        for (groupByInput in groupByItemInputs) {
            val candidate = availableSet.filterBySpecPatterns(listOf(groupByInput.specPattern))
            val candidateSpecs: List<LinkableInstanceSpec> =
                candidate.specs.filterIsInstance<LinkableInstanceSpec>()
            when {
                candidateSpecs.size == 1 -> {
                    val matched = candidateSpecs[0]
                    val withAlias = groupByInput.alias?.let { matched.withAlias(it) } ?: matched
                    matchedSpecs.add(withAlias)
                    matchedSets.add(candidate)
                }
                candidateSpecs.isEmpty() -> {
                    val sinkPath = MetricFlowQueryResolutionPath.fromPathItem(resolutionDag.sinkNode)
                    val issue = if (metricReferences.isEmpty()) {
                        cc.monomer.metricflow.domain.query.issue
                            .group_by_item_resolver.NoMatchingItemsForNoMetricsQueryIssue
                            .fromParameters(queryResolutionPath = sinkPath)
                    } else {
                        // Pick an arbitrary metric reference to anchor the issue on. The
                        // push-down visitor (W14c) would attribute this to the specific
                        // metric whose intersection rejected the input; the W14a shell
                        // keeps the issue at query level.
                        cc.monomer.metricflow.domain.query.issue
                            .group_by_item_resolver.NoMatchingItemsForSimpleMetricIssue
                            .fromParameters(
                                metricName = metricReferences.first().elementName,
                                queryResolutionPath = sinkPath,
                            )
                    }
                    mappingItems.add(
                        InputToIssueSetMappingItem(
                            resolverInput = groupByInput,
                            issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(issue),
                        ),
                    )
                }
                else -> {
                    mappingItems.add(
                        InputToIssueSetMappingItem(
                            resolverInput = groupByInput,
                            issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                                cc.monomer.metricflow.domain.query.issue
                                    .group_by_item_resolver.AmbiguousGroupByItemIssue
                                    .fromParameters(
                                        matchingSpecs = candidateSpecs,
                                        queryResolutionPath = MetricFlowQueryResolutionPath
                                            .fromPathItem(resolutionDag.sinkNode),
                                    ),
                            ),
                        ),
                    )
                }
            }
        }

        val linkableElementSet: GroupByItemSet = if (matchedSets.isEmpty()) {
            GroupByItemSet.EMPTY
        } else {
            matchedSets.first().union(*matchedSets.drop(1).toTypedArray())
        }

        return GroupByItemResolutionResult(
            resolutionDag = resolutionDag,
            groupByItemSpecs = matchedSpecs,
            mapping = InputToIssueSetMapping(items = mappingItems),
            linkableElementSet = linkableElementSet,
        )
    }

    /**
     * Compute the intersection of available group-by items across [metricReferences].
     *
     * For a no-metric query we delegate to
     * [cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver.resolveAvailableItemsForNoMetricsInQuery].
     *
     * For one metric we return its set directly. For multiple metrics we
     * intersect — the same pattern as
     * [cc.monomer.metricflow.application.engine.MetricFlowEngine]'s
     * `resolveCommonGroupByItems` helper.
     */
    private fun computeAvailableGroupByItemSet(
        metricReferences: List<MetricReference>,
    ): GroupByItemSet {
        val resolver = graphLookup.groupByItemSetResolver
        if (metricReferences.isEmpty()) {
            return resolver.resolveAvailableItemsForNoMetricsInQuery()
        }
        val sets = metricReferences.map { resolver.resolveAvailableItemsForMetric(it) }
        return if (sets.size == 1) sets[0] else sets.first().intersection(*sets.drop(1).toTypedArray())
    }

    // --- Step 6: order-by --------------------------------------------------

    /** Result of [resolveOrderBy]. */
    private data class OrderByResolution(
        val orderBySpecs: List<OrderBySpec>,
        val mapping: InputToIssueSetMapping,
    )

    /**
     * Match each order-by input to one of the resolved metric/group-by specs.
     *
     * Python uses `OrderByHelper` for alias-bound buckets; the Kotlin port
     * matches against the full union (alias-bound order-by is not exercised
     * by the corpus and a Phase 5 polish item).
     */
    private fun resolveOrderBy(
        resolverInputsForOrderByItems: List<ResolverInputForOrderByItem>,
        metricSpecs: List<MetricSpec>,
        groupByItemSpecs: List<LinkableInstanceSpec>,
        queryResolutionPath: MetricFlowQueryResolutionPath,
    ): OrderByResolution {
        val mappingItems = mutableListOf<InputToIssueSetMappingItem>()
        val orderBySpecs = LinkedHashSet<OrderBySpec>()
        val allSpecs: List<InstanceSpec> = (metricSpecs as List<InstanceSpec>) + groupByItemSpecs

        for (orderByInput in resolverInputsForOrderByItems) {
            val matched = mutableSetOf<InstanceSpec>()
            for (possible in orderByInput.possibleInputs) {
                val specPattern = possible.inputPatternDescription?.specPattern ?: continue
                matched.addAll(specPattern.match(allSpecs))
            }
            if (matched.size != 1) {
                mappingItems.add(
                    InputToIssueSetMappingItem(
                        resolverInput = orderByInput,
                        issueSet = MetricFlowQueryResolutionIssueSet.fromIssue(
                            InvalidOrderByItemIssue.fromParameters(
                                orderByItem = orderByInput.inputObj.toString(),
                                queryResolutionPath = queryResolutionPath,
                            ),
                        ),
                    ),
                )
            } else {
                orderBySpecs.add(
                    OrderBySpec(
                        instanceSpec = matched.first(),
                        descending = orderByInput.descending,
                    ),
                )
            }
        }
        return OrderByResolution(
            orderBySpecs = orderBySpecs.toList(),
            mapping = InputToIssueSetMapping(items = mappingItems),
        )
    }

    // --- Step 7: where-filter lookup --------------------------------------

    /**
     * Build the where-filter spec lookup for [resolutionDag].
     *
     * Currently delegates to the W8 placeholder factory which returns
     * [FilterSpecResolutionLookUp.EMPTY] (the Jinja sandbox is deferred to a
     * later wave — see the README's "Deferred (Jinja-dependent)" section).
     * The placeholder still preserves the call-graph shape; once the Jinja
     * port lands, only this method needs to swap implementations.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun buildFilterSpecLookup(
        resolutionDag: GroupByItemResolutionDag,
    ): FilterSpecResolutionLookUp {
        // The W8 WhereFilterSpecResolver is not yet ported; reading the
        // `whereFilterPatternFactory` keeps the parameter live for forward
        // compatibility (the field is consumed by the deferred sandbox).
        @Suppress("UNUSED_VARIABLE")
        val factory = whereFilterPatternFactory
        return FilterSpecResolutionLookUp.EMPTY
    }

    // --- Step 8: post-resolution validation -------------------------------

    /**
     * Drive the three W8 validation rules over the resolution DAG.
     *
     * Mirrors Python's `PostResolutionQueryValidator.validate_query` —
     * walk every metric node in the DAG, fire the per-metric callbacks,
     * fire the query-level callback at the sink, and merge the
     * resulting issue sets.
     */
    private fun runValidationRules(
        resolutionDag: GroupByItemResolutionDag,
        resolverInputForQuery: ResolverInputForQuery,
        resolveMetricResult: ResolveMetricsResult,
        resolveGroupByItemResult: ResolveGroupByItemsResult,
    ): MetricFlowQueryResolutionIssueSet {
        val rules = listOf(
            MetricTimeQueryValidationRule(
                manifestLookup = manifestLookup,
                resolverInputForQuery = resolverInputForQuery,
                resolveGroupByItemResult = resolveGroupByItemResult,
                resolveMetricResult = resolveMetricResult,
            ),
            DuplicateMetricValidationRule(
                manifestLookup = manifestLookup,
                resolverInputForQuery = resolverInputForQuery,
                resolveGroupByItemResult = resolveGroupByItemResult,
                resolveMetricResult = resolveMetricResult,
            ),
            UniqueOutputColumnValidationRule(
                manifestLookup = manifestLookup,
                resolverInputForQuery = resolverInputForQuery,
                resolveGroupByItemResult = resolveGroupByItemResult,
                resolveMetricResult = resolveMetricResult,
            ),
        )
        return PostResolutionQueryValidator(rules).validate(resolutionDag)
    }

    // --- Step 9: queried-semantic-models accounting ------------------------

    /**
     * Collect the semantic-model references touched by this query.
     *
     * Mirrors Python's combination of:
     *
     * - models from the resolved group-by linkable element set
     * - models from the filter-spec lookup (today: none, deferred)
     * - models from simple metrics in the resolution DAG
     *
     * with the [SemanticModelDerivation.VIRTUAL_SEMANTIC_MODEL_REFERENCE]
     * filtered out.
     */
    private fun collectQueriedSemanticModels(
        resolutionDag: GroupByItemResolutionDag,
        groupByItemSet: GroupByItemSet,
        filterSpecLookup: FilterSpecResolutionLookUp,
    ): List<SemanticModelReference> {
        val collected = LinkedHashSet<SemanticModelReference>()
        collected.addAll(groupByItemSet.derivedFromSemanticModels)
        for (resolution in filterSpecLookup.specResolutions) {
            collected.addAll(resolution.resolvedGroupByItemSet.derivedFromSemanticModels)
        }
        collected.addAll(simpleMetricSemanticModels(resolutionDag))
        collected.remove(SemanticModelDerivation.VIRTUAL_SEMANTIC_MODEL_REFERENCE)
        val known = manifestLookup.semanticManifest.semanticModels
            .map { SemanticModelReference(it.name) }
            .toSet()
        return collected.filter { it in known }.sortedBy { it.semanticModelName }
    }

    /**
     * Walk the DAG looking for simple-metric source nodes and the
     * conversion-metric inputs of complex nodes; return the semantic-model
     * references that own each one.
     *
     * Mirrors Python's `_get_models_for_simple_metrics`.
     */
    private fun simpleMetricSemanticModels(
        resolutionDag: GroupByItemResolutionDag,
    ): List<SemanticModelReference> {
        val nodeSet = resolutionDag.sinkNode.inclusiveAncestors()

        val simpleMetricRefs = LinkedHashSet<MetricReference>()
        for (simpleMetricNode in nodeSet.simpleMetricNodes) {
            simpleMetricRefs.add(simpleMetricNode.metricReference)
        }
        for (complexNode in nodeSet.complexMetricNodes) {
            val metric = manifestLookup.metricLookup.getMetric(complexNode.metricReference)
            val conversion = metric.typeParams.conversionTypeParams ?: continue
            conversion.baseMetric?.let { simpleMetricRefs.add(MetricReference(it.name)) }
            conversion.conversionMetric?.let { simpleMetricRefs.add(MetricReference(it.name)) }
        }

        val out = LinkedHashSet<SemanticModelReference>()
        for (simpleMetricRef in simpleMetricRefs) {
            val metric = manifestLookup.metricLookup.getMetric(simpleMetricRef)
            val aggregationParams = metric.typeParams.metricAggregationParams
                ?: throw cc.monomer.metricflow.common.errors.MetricFlowInternalError(
                    "Metric '${simpleMetricRef.elementName}' does not have `metric_aggregation_params` set.",
                )
            out.add(SemanticModelReference(aggregationParams.semanticModel))
        }
        return out.toList()
    }

    // --- shared helpers ---------------------------------------------------

    private fun mergeIterable(mappings: List<InputToIssueSetMapping>): InputToIssueSetMapping =
        mappings.fold(InputToIssueSetMapping.EMPTY) { acc, next -> acc.merge(next) }

    /** Maintained for forward compatibility — call from outside the resolver if needed. */
    @Suppress("unused")
    fun annotatedSpecsForMetricSet(metricReferences: List<MetricReference>): List<AnnotatedSpec> =
        computeAvailableGroupByItemSet(metricReferences).annotatedSpecs
}

/**
 * Drives the W8 post-resolution validation rules against a
 * [GroupByItemResolutionDag] and aggregates the resulting issues.
 *
 * Port of `metricflow_semantics.query.validation_rules.query_validator.PostResolutionQueryValidator`.
 *
 * Pure structural visitor — each concrete validation rule decides whether
 * it has anything to say at the simple-metric, complex-metric, and query
 * callbacks; this visitor only schedules the calls and merges results.
 */
internal class PostResolutionQueryValidator(
    private val rules: List<cc.monomer.metricflow.domain.query.validation
        .PostResolutionQueryValidationRule>,
) {
    fun validate(resolutionDag: GroupByItemResolutionDag): MetricFlowQueryResolutionIssueSet {
        val visitor = ValidationVisitor(rules)
        return resolutionDag.sinkNode.accept(visitor)
    }

    private class ValidationVisitor(
        private val rules: List<cc.monomer.metricflow.domain.query.validation
            .PostResolutionQueryValidationRule>,
    ) : cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
        .GroupByItemResolutionNodeVisitor<MetricFlowQueryResolutionIssueSet> {

        private val pathStack: ArrayDeque<
            cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
                .GroupByItemResolutionNode
            > = ArrayDeque()

        private fun pathSnapshot(): MetricFlowQueryResolutionPath =
            MetricFlowQueryResolutionPath(resolutionPathNodes = pathStack.toList())

        private fun <R> withNode(
            node: cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
                .GroupByItemResolutionNode,
            block: () -> R,
        ): R {
            pathStack.addLast(node)
            try {
                return block()
            } finally {
                pathStack.removeLast()
            }
        }

        override fun visitSimpleMetricNode(
            node: cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
                .SimpleMetricGroupByItemSourceNode,
        ): MetricFlowQueryResolutionIssueSet = withNode(node) {
            val mergedFromParents = mergeFromParents(node.parentNodes)
            val perRule = rules.map {
                it.validateSimpleMetricInResolutionDag(
                    metricReference = node.metricReference,
                    resolutionPath = pathSnapshot(),
                )
            }
            mergeAll(listOf(mergedFromParents) + perRule)
        }

        override fun visitNoMetricsQueryNode(
            node: cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
                .NoMetricsGroupByItemSourceNode,
        ): MetricFlowQueryResolutionIssueSet = withNode(node) {
            mergeFromParents(node.parentNodes)
        }

        override fun visitComplexMetricNode(
            node: ComplexMetricGroupByItemResolutionNode,
        ): MetricFlowQueryResolutionIssueSet = withNode(node) {
            val mergedFromParents = mergeFromParents(node.parentNodes)
            val perRule = rules.map {
                it.validateComplexMetricInResolutionDag(
                    metricReference = node.metricReference,
                    resolutionPath = pathSnapshot(),
                )
            }
            mergeAll(listOf(mergedFromParents) + perRule)
        }

        override fun visitQueryNode(
            node: QueryGroupByItemResolutionNode,
        ): MetricFlowQueryResolutionIssueSet = withNode(node) {
            val mergedFromParents = mergeFromParents(node.parentNodes)
            val perRule = rules.map {
                it.validateQueryInResolutionDag(
                    metricsInQuery = node.metricsInQuery,
                    whereFilterIntersection = node.whereFilterIntersection,
                    resolutionPath = pathSnapshot(),
                )
            }
            mergeAll(listOf(mergedFromParents) + perRule)
        }

        private fun mergeFromParents(
            parents: List<
                cc.monomer.metricflow.domain.query.group_by.resolution_dag.node
                    .GroupByItemResolutionNode
                >,
        ): MetricFlowQueryResolutionIssueSet =
            mergeAll(parents.map { it.accept(this) })

        private fun mergeAll(
            sets: List<MetricFlowQueryResolutionIssueSet>,
        ): MetricFlowQueryResolutionIssueSet =
            sets.fold(MetricFlowQueryResolutionIssueSet.EMPTY) { acc, next -> acc.merge(next) }
    }
}
