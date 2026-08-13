package cc.monomer.metricflow.domain.query.validation

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.input.ResolverInputForQuery
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * Aggregate of the resolver's metric-resolution results, passed into
 * post-resolution rules.
 *
 * Mirrors Python's `ResolveMetricsResult`. The full Python record lives
 * inside `metricflow_semantics.query.query_resolver`; we keep only the
 * `metric_specs` field today because validation rules don't read anything
 * else from it. Extra fields can be added when the resolver ports in W9.
 */
data class ResolveMetricsResult(
    val metricSpecs: List<MetricSpec>,
)

/**
 * Aggregate of the resolver's group-by-item resolution results.
 *
 * Mirrors Python's `ResolveGroupByItemsResult`.
 */
data class ResolveGroupByItemsResult(
    val groupByItemSpecs: List<InstanceSpec>,
)

/**
 * Validation rule that runs after every query input has been resolved.
 *
 * Port of
 * `metricflow_semantics.query.validation_rules.base_validation_rule.PostResolutionQueryValidationRule`.
 *
 * Concrete subclasses receive the resolver's manifest lookup, original
 * resolver input, resolved metric specs, and resolved group-by-item specs
 * via the constructor. They then implement up to three callbacks fired
 * by the resolver while walking the resolution DAG.
 */
abstract class PostResolutionQueryValidationRule(
    protected val manifestLookup: SemanticManifestLookup,
    protected val resolverInputForQuery: ResolverInputForQuery,
    protected val resolveGroupByItemResult: ResolveGroupByItemsResult,
    protected val resolveMetricResult: ResolveMetricsResult,
) {
    /**
     * Validate a simple metric encountered during DAG traversal.
     *
     * Called once per simple-metric source node.
     */
    abstract fun validateSimpleMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet

    /**
     * Validate a derived metric encountered during DAG traversal.
     *
     * Called once per complex-metric resolution node.
     */
    abstract fun validateComplexMetricInResolutionDag(
        metricReference: MetricReference,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet

    /**
     * Validate the query node itself.
     *
     * Called once at the query node — the sink of the resolution DAG.
     */
    abstract fun validateQueryInResolutionDag(
        metricsInQuery: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection,
        resolutionPath: MetricFlowQueryResolutionPath,
    ): MetricFlowQueryResolutionIssueSet
}
