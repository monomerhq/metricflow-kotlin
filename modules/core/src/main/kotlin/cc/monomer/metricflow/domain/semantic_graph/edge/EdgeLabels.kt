package cc.monomer.metricflow.domain.semantic_graph.edge

import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel

/**
 * Labels attached to semantic-graph edges.
 *
 * Port of `metricflow_semantics/semantic_graph/edges/edge_labels.py`.
 *
 * Each label encodes a traversal-time constraint or hint. They are consumed by
 * the pathfinder and the resolver to gate which paths can produce which kinds
 * of group-by items.
 */
sealed interface SemanticGraphEdgeLabel : MetricFlowGraphLabel

/**
 * Label for an edge from a cumulative-metric node to a simple-metric node.
 *
 * Helps address special cases with cumulative metrics (e.g. ability to query
 * by date part).
 */
data object CumulativeMetricLabel : SemanticGraphEdgeLabel

/**
 * Label for edges that, when added to a path, should not allow querying of the date part.
 */
data object DenyDatePartLabel : SemanticGraphEdgeLabel

/**
 * Label for edges that, when added to a path, should not allow querying of
 * only the entity-key attributes.
 *
 * E.g. for time-offset metrics, the successor edges have this label as those
 * metrics must be queried with `metric_time`.
 */
data object DenyEntityKeyQueryResolutionLabel : SemanticGraphEdgeLabel

/** Label for successor edges from a conversion metric to the input conversion metric node. */
data object ConversionMetricLabel : SemanticGraphEdgeLabel

/**
 * Label for edges that, when added to a path, should not affect visibility of group-by items.
 *
 * E.g. for conversion metrics, the edge from the conversion-metric node to the
 * conversion-measure node is given this label as the conversion measure does
 * not affect the available group-by items for the metric.
 */
data object DenyVisibleAttributesLabel : SemanticGraphEdgeLabel
