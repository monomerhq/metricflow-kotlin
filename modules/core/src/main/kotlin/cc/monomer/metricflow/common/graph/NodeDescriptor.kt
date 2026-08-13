package cc.monomer.metricflow.common.graph

/**
 * Descriptor for a node to allow lookups by strings.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.node_descriptor.MetricFlowGraphNodeDescriptor`.
 */
data class MetricFlowGraphNodeDescriptor(
    val nodeName: String,
    /** A name to cluster nodes — used for visualization only. */
    val clusterName: String?,
)
