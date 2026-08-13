package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.errors.MetricFlowInternalError

/**
 * Raised when an unexpected condition is encountered during semantic-graph traversal.
 *
 * Port of `metricflow_semantics/semantic_graph/sg_exceptions.py::SemanticGraphTraversalError`.
 */
class SemanticGraphTraversalError(message: String) : MetricFlowInternalError(message)
