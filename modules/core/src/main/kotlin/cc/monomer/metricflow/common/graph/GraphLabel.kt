package cc.monomer.metricflow.common.graph

/**
 * Base interface for objects that can be used to look up nodes / edges in a graph.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.graph_labeling.MetricFlowGraphLabel`.
 *
 * In Python the base class also mixes in [Comparable]; Kotlin's stdlib
 * already provides `Comparable<T>` and concrete label classes implement it
 * directly when ordering matters.
 */
interface MetricFlowGraphLabel
