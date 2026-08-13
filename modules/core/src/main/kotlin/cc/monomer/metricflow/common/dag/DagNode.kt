package cc.monomer.metricflow.common.dag

import cc.monomer.metricflow.common.logging.MetricFlowPrettyFormattable
import cc.monomer.metricflow.common.util.Visitable

/**
 * Unique identifier for nodes in DAGs.
 *
 * Port of `metricflow_semantics.dag.mf_dag.NodeId`. Wraps the underlying
 * sequential id string and exposes a [createUnique] factory that consumes an
 * [IdPrefix].
 */
@JvmInline
value class NodeId(val idStr: String) : Comparable<NodeId> {
    override fun compareTo(other: NodeId): Int = idStr.compareTo(other.idStr)
    override fun toString(): String = idStr

    companion object {
        fun createUnique(idPrefix: IdPrefix): NodeId =
            NodeId(SequentialIdGenerator.createNextId(idPrefix).strValue)
    }
}

/**
 * Unique identifier for DAGs themselves.
 *
 * Port of `metricflow_semantics.dag.mf_dag.DagId`.
 */
@JvmInline
value class DagId(val idStr: String) : Comparable<DagId> {
    override fun compareTo(other: DagId): Int = idStr.compareTo(other.idStr)
    override fun toString(): String = idStr

    companion object {
        fun fromString(idStr: String): DagId = DagId(idStr)
        fun fromIdPrefix(idPrefix: IdPrefix): DagId =
            DagId(SequentialIdGenerator.createNextId(idPrefix).strValue)
    }
}

/**
 * A key/value pair used when rendering a node into a visualization.
 *
 * Port of `metricflow_semantics.dag.mf_dag.DisplayedProperty`. Renders as
 * `key = str(value)` per the Python convention.
 */
data class DisplayedProperty(val key: String, val value: Any?)

/**
 * A node in a DAG. These should be immutable.
 *
 * Port of `metricflow_semantics.dag.mf_dag.DagNode`. The Python base class
 * keeps `parent_nodes`, a `description` abstract property, and a
 * `displayed_properties` virtual property; we restate the same surface in
 * Kotlin.
 *
 * Subclasses must:
 * 1. Pass [parentNodes] up via the `parentNodes` constructor parameter.
 * 2. Override [description] to return a human-readable string.
 * 3. Override [idPrefix] (used to mint [NodeId]s).
 */
abstract class DagNode<T : DagNode<T>>(
    val parentNodes: List<T>,
) : MetricFlowPrettyFormattable, Visitable {

    /** Auto-assigned at construction. Use [nodeId] to look it up. */
    val nodeId: NodeId = NodeId.createUnique(idPrefix())

    /** A human-readable description for this node. */
    abstract val description: String

    /** Subclasses provide their prefix used to mint a fresh [NodeId]. */
    protected abstract fun idPrefix(): IdPrefix

    /** Properties displayed alongside this node in visualizations. */
    open val displayedProperties: List<DisplayedProperty>
        get() = listOf(
            DisplayedProperty("description", description),
            DisplayedProperty("node_id", nodeId),
        )

    override fun prettyFormat(): String = "${this::class.simpleName}(node_id=${nodeId.idStr})"
}

/**
 * Represents a directed acyclic graph. The sink nodes will have the
 * connected components as ancestors.
 *
 * Port of `metricflow_semantics.dag.mf_dag.MetricFlowDag`.
 */
open class MetricFlowDag<T : DagNode<T>>(
    val dagId: DagId,
    val sinkNodes: List<T>,
)
