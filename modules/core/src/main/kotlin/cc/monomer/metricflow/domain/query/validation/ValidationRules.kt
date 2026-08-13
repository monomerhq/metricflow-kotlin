package cc.monomer.metricflow.domain.query.validation

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.ResolverInputForQuery
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssue
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.query.issue.parsing.CumulativeMetricRequiresMetricTimeIssue
import cc.monomer.metricflow.domain.query.issue.parsing.DuplicateMetricIssue
import cc.monomer.metricflow.domain.query.issue.parsing.DuplicateOutputColumnIssue
import cc.monomer.metricflow.domain.query.issue.parsing.OffsetMetricRequiresMetricTimeIssue
import cc.monomer.metricflow.domain.spec.DunderColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec

/**
 * Reject queries that repeat the same metric name.
 *
 * Port of `metricflow_semantics.query.validation_rules.duplicate_metric.DuplicateMetricValidationRule`.
 *
 * Only [validateQueryInResolutionDag] does any work — duplicate-metric
 * detection is a query-node property.
 */
class DuplicateMetricValidationRule(
    manifestLookup: SemanticManifestLookup,
    resolverInputForQuery: ResolverInputForQuery,
    resolveGroupByItemResult: ResolveGroupByItemsResult,
    resolveMetricResult: ResolveMetricsResult,
) : PostResolutionQueryValidationRule(
    manifestLookup,
    resolverInputForQuery,
    resolveGroupByItemResult,
    resolveMetricResult,
) {

    override fun validateSimpleMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY

    override fun validateComplexMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY

    override fun validateQueryInResolutionDag(
        metricsInQuery: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet {
        val counts = LinkedHashMap<MetricReference, Int>()
        for (metricReference in metricsInQuery) counts[metricReference] = (counts[metricReference] ?: 0) + 1
        val duplicates = counts.filterValues { it > 1 }.keys.toList().sortedBy { it.elementName }
        if (duplicates.isEmpty()) return MetricFlowQueryResolutionIssueSet.EMPTY
        return MetricFlowQueryResolutionIssueSet.fromIssue(
            DuplicateMetricIssue.fromParameters(
                duplicateMetricNames = duplicates.map { it.elementName },
                queryResolutionPath = resolutionPath,
            ),
        )
    }
}

/**
 * Reject queries that produce two columns with the same output name.
 *
 * Port of
 * `metricflow_semantics.query.validation_rules.unique_column_names.UniqueOutputColumnValidationRule`.
 *
 * Uses [DunderColumnAssociationResolver] (W7b) to map each resolved spec
 * to its column name and flags repeats.
 */
class UniqueOutputColumnValidationRule(
    manifestLookup: SemanticManifestLookup,
    resolverInputForQuery: ResolverInputForQuery,
    resolveGroupByItemResult: ResolveGroupByItemsResult,
    resolveMetricResult: ResolveMetricsResult,
) : PostResolutionQueryValidationRule(
    manifestLookup,
    resolverInputForQuery,
    resolveGroupByItemResult,
    resolveMetricResult,
) {

    override fun validateSimpleMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY

    override fun validateComplexMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY

    override fun validateQueryInResolutionDag(
        metricsInQuery: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet {
        // Python defaults dunder_prefix_simple_metric_inputs=True; mirror that here.
        val resolver = DunderColumnAssociationResolver(dunderPrefixSimpleMetricInputs = true)
        val seen = mutableSetOf<String>()
        val duplicates = mutableListOf<String>()
        val allSpecs = resolveMetricResult.metricSpecs + resolveGroupByItemResult.groupByItemSpecs
        for (spec in allSpecs) {
            val columnName = resolver.resolveSpec(spec).columnName
            if (columnName in seen) duplicates.add(columnName)
            seen.add(columnName)
        }
        if (duplicates.isEmpty()) return MetricFlowQueryResolutionIssueSet.EMPTY
        return MetricFlowQueryResolutionIssueSet.fromIssue(
            DuplicateOutputColumnIssue.fromParameters(
                duplicateColumnNames = duplicates,
                queryResolutionPath = resolutionPath,
            ),
        )
    }
}

/**
 * Detect cases where a query references a cumulative or time-offset metric
 * but does not include `metric_time` (or an equivalent aggregation time
 * dimension).
 *
 * Port of
 * `metricflow_semantics.query.validation_rules.metric_time_requirements.MetricTimeQueryValidationRule`.
 *
 * **W8 deferral.** The Python rule consults two methods that haven't
 * landed yet:
 *
 * - `ResolveGroupByItemsResult.linkable_element_set` (resolver internal
 *   not yet ported).
 * - `MetricLookup.get_aggregation_time_dimension_specs` (W7a deferred to
 *   W8 — but that requires the full resolver semantics, see the W7b
 *   README's deferral list).
 *
 * To keep the structural shape stable, this rule:
 * - reads the group-by-item specs directly from
 *   [ResolveGroupByItemsResult.groupByItemSpecs] and checks for
 *   `metric_time` by dunder-name string match,
 * - skips the agg-time-dimension fallback — when the full resolver
 *   wiring arrives we'll add it back via the manifest lookup.
 *
 * The downstream W9 dataflow planner does not currently depend on the
 * agg-time-dimension fallback; the corpus test suite continues to pass.
 */
class MetricTimeQueryValidationRule(
    manifestLookup: SemanticManifestLookup,
    resolverInputForQuery: ResolverInputForQuery,
    resolveGroupByItemResult: ResolveGroupByItemsResult,
    resolveMetricResult: ResolveMetricsResult,
) : PostResolutionQueryValidationRule(
    manifestLookup,
    resolverInputForQuery,
    resolveGroupByItemResult,
    resolveMetricResult,
) {
    /** True if any group-by-item spec carries the dunder name `metric_time`. */
    private val queryIncludesMetricTime: Boolean = resolveGroupByItemResult.groupByItemSpecs.any { spec ->
        (spec as? LinkableInstanceSpec)?.elementName == "metric_time"
    }

    private fun cumulativeNeedsMetricTime(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): List<MetricFlowQueryResolutionIssue> {
        val metric = manifestLookup.metricLookup.getMetric(metricReference)
        val cumulativeTypeParams = metric.typeParams.cumulativeTypeParams
        if (cumulativeTypeParams == null ||
            (cumulativeTypeParams.window == null && cumulativeTypeParams.grainToDate == null)
        ) {
            return emptyList()
        }
        if (queryIncludesMetricTime) return emptyList()
        return listOf(
            CumulativeMetricRequiresMetricTimeIssue.fromParameters(
                metricName = metricReference.elementName,
                queryResolutionPath = resolutionPath,
            ),
        )
    }

    private fun derivedNeedsMetricTime(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): List<MetricFlowQueryResolutionIssue> {
        val metric = manifestLookup.metricLookup.getMetric(metricReference)
        val hasTimeOffset = metric.inputMetrics.any {
            it.offsetWindow != null || it.offsetToGrain != null
        }
        if (!hasTimeOffset) return emptyList()
        if (queryIncludesMetricTime) return emptyList()
        return listOf(
            OffsetMetricRequiresMetricTimeIssue.fromParameters(
                metricName = metricReference.elementName,
                queryResolutionPath = resolutionPath,
            ),
        )
    }

    override fun validateComplexMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet {
        val metric = manifestLookup.metricLookup.getMetric(metricReference)
        val issues: List<MetricFlowQueryResolutionIssue> = when (metric.type) {
            MetricType.CUMULATIVE -> cumulativeNeedsMetricTime(metricReference, resolutionPath)
            MetricType.RATIO, MetricType.DERIVED -> derivedNeedsMetricTime(metricReference, resolutionPath)
            MetricType.SIMPLE, MetricType.CONVERSION -> emptyList()
        }
        return if (issues.isEmpty()) MetricFlowQueryResolutionIssueSet.EMPTY else MetricFlowQueryResolutionIssueSet(issues)
    }

    override fun validateQueryInResolutionDag(
        metricsInQuery: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY

    override fun validateSimpleMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet = MetricFlowQueryResolutionIssueSet.EMPTY
}
