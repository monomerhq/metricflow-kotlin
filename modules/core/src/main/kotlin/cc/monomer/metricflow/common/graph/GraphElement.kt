package cc.monomer.metricflow.common.graph

import cc.monomer.metricflow.common.dag.DisplayedProperty

/**
 * Mixin for classes whose [displayedProperties] should be included in visualizations.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.graph_element.HasDisplayedProperty`.
 */
interface HasDisplayedProperty {
    val displayedProperties: List<DisplayedProperty> get() = emptyList()
}

/**
 * An element in a graph (e.g. node, edge).
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.graph_element.MetricFlowGraphElement`.
 */
interface MetricFlowGraphElement : HasDisplayedProperty
