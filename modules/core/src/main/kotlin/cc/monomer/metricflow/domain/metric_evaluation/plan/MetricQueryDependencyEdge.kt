package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.common.graph.MetricFlowGraphEdge
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * An edge in the [MetricEvaluationPlan] that captures a metric-to-input-metric
 * dependency.
 *
 * Port of `metricflow.metric_evaluation.plan.me_edges.MetricQueryDependencyEdge`.
 *
 * The edge points from a target node (which computes a derived metric) to a
 * source node (which computes one of the inputs). This direction is analogous
 * to the way a derived metric definition lists its input metrics in the
 * configuration.
 *
 * For example, if a node A computes `bookings_per_listing` from source nodes B
 * (computes `bookings`) and C (computes `listings`), two edges capture the
 * dependencies:
 *
 *     A (target_node_output_spec: bookings_per_listing) → B (source_node_output_spec: bookings)
 *     A (target_node_output_spec: bookings_per_listing) → C (source_node_output_spec: listings)
 */
class MetricQueryDependencyEdge(
    targetNode: MetricQueryNode,
    sourceNode: MetricQueryNode,
    /** The output spec on the target node that depends on [sourceNodeOutputSpec]. */
    val targetNodeOutputSpec: MetricSpec,
    /** The output spec from the source node that satisfies [targetNodeOutputSpec]. */
    val sourceNodeOutputSpec: MetricSpec,
) : MetricFlowGraphEdge<MetricQueryNode>(tailNode = targetNode, headNode = sourceNode) {

    /** The target (dependent) node — alias for [tailNode]. */
    val targetNode: MetricQueryNode get() = tailNode

    /** The source (dependency) node — alias for [headNode]. */
    val sourceNode: MetricQueryNode get() = headNode

    override fun inverse(): MetricQueryDependencyEdge =
        throw NotImplementedError("The inverse graph is not yet implemented.")

    override fun equals(other: Any?): Boolean =
        other is MetricQueryDependencyEdge &&
            tailNode == other.tailNode &&
            headNode == other.headNode &&
            targetNodeOutputSpec == other.targetNodeOutputSpec &&
            sourceNodeOutputSpec == other.sourceNodeOutputSpec

    override fun hashCode(): Int {
        var result = tailNode.hashCode()
        result = 31 * result + headNode.hashCode()
        result = 31 * result + targetNodeOutputSpec.hashCode()
        result = 31 * result + sourceNodeOutputSpec.hashCode()
        return result
    }

    override fun toString(): String =
        "MetricQueryDependencyEdge(${tailNode.nodeDescriptor.nodeName} -> ${headNode.nodeDescriptor.nodeName}, " +
            "targetSpec=$targetNodeOutputSpec, sourceSpec=$sourceNodeOutputSpec)"

    companion object {
        /** Factory mirroring Python `MetricQueryDependencyEdge.create`. */
        fun create(
            targetNode: MetricQueryNode,
            targetNodeOutputSpec: MetricSpec,
            sourceNode: MetricQueryNode,
            sourceNodeOutputSpec: MetricSpec,
        ): MetricQueryDependencyEdge = MetricQueryDependencyEdge(
            targetNode = targetNode,
            sourceNode = sourceNode,
            targetNodeOutputSpec = targetNodeOutputSpec,
            sourceNodeOutputSpec = sourceNodeOutputSpec,
        )
    }
}
