package cc.monomer.metricflow.domain.semantic_graph.node

import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel

/**
 * Labels attached to semantic-graph nodes.
 *
 * Port of `metricflow_semantics/semantic_graph/nodes/node_labels.py`.
 *
 * Each label is a singleton-style sealed-hierarchy entry whose only behaviour
 * is to mark a node for label-based lookups. Python implements them as
 * `Singleton` dataclasses; in Kotlin we use `data object` for the no-state
 * cases and `data class` for [MetricLabel] which carries an optional metric
 * name.
 */
sealed interface SemanticGraphNodeLabel : MetricFlowGraphLabel

/** Marks any attribute node that can be used in the group-by argument of an MF query. */
data object GroupByAttributeLabel : SemanticGraphNodeLabel

/** Marks nodes that correspond to entities configured in a semantic model. */
data object ConfiguredEntityLabel : SemanticGraphNodeLabel

/** Marks time-dimension nodes. */
data object TimeDimensionLabel : SemanticGraphNodeLabel

/** Marks nodes that should be clustered together in the `time` section. */
data object TimeClusterLabel : SemanticGraphNodeLabel

/** Marks the node that represents `metric_time`. */
data object MetricTimeLabel : SemanticGraphNodeLabel

/** Marks every node that corresponds to a configured metric. */
data class MetricLabel(val metricName: String?) : SemanticGraphNodeLabel

/** Marks nodes that represent a simple metric. */
data object SimpleMetricLabel : SemanticGraphNodeLabel

/** Marks nodes that represent a complex metric. */
data object ComplexMetricLabel : SemanticGraphNodeLabel

/** Marks nodes that represent a joined semantic model. */
data object JoinedModelLabel : SemanticGraphNodeLabel

/** Marks nodes that represent a local semantic model (the same model as the measure). */
data object LocalModelLabel : SemanticGraphNodeLabel

/** Marks nodes that correspond to entity-key attribute nodes. */
data object KeyAttributeLabel : SemanticGraphNodeLabel
