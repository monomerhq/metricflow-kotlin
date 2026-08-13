package cc.monomer.metricflow.common.graph

import java.util.concurrent.atomic.AtomicInteger

/**
 * Identifier for a graph.
 *
 * Port of `metricflow_semantics.toolkit.mf_graph.graph_id.MetricFlowGraphId`.
 */
interface MetricFlowGraphId {
    val strValue: String
}

/**
 * Graph IDs generated sequentially. Port of `SequentialGraphId`.
 *
 * The Python counter is process-global and thread-safe via `itertools.count()`;
 * we use an [AtomicInteger] for the same property.
 */
class SequentialGraphId : MetricFlowGraphId {

    private val cached: String = "id_${counter.getAndIncrement()}"

    override val strValue: String get() = cached

    override fun equals(other: Any?): Boolean = other is SequentialGraphId && cached == other.cached
    override fun hashCode(): Int = cached.hashCode()
    override fun toString(): String = cached

    companion object {
        private val counter = AtomicInteger(0)
        fun create(): SequentialGraphId = SequentialGraphId()
    }
}
