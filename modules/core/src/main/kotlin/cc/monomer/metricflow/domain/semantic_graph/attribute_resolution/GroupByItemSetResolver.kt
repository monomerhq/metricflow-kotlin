package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.domain.manifest.model.references.MetricReference

/**
 * Interface for resolving the set of group-by items available for a metric.
 *
 * Port of `metricflow_semantics/model/semantics/linkable_spec_resolver.py::GroupByItemSetResolver`.
 *
 * Concrete implementation: [SemanticGraphGroupByItemSetResolver]. The Python
 * codebase ships two implementations (the legacy linkable-spec resolver and
 * the newer semantic-graph one); only the latter is on our porting path.
 */
interface GroupByItemSetResolver {
    /**
     * Return the [GroupByItemSet] of items available when querying [metricReference].
     *
     * For multi-metric queries, callers intersect the result of every
     * single-metric call.
     */
    fun resolveAvailableItemsForMetric(metricReference: MetricReference): GroupByItemSet

    /** Return the set of items available when no metric constraint applies. */
    fun resolveAvailableItemsForNoMetricsInQuery(): GroupByItemSet
}
