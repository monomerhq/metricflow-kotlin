package cc.monomer.metricflow.domain.query.group_by.resolution_dag

import cc.monomer.metricflow.domain.query.group_by.PathPrefixable
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.GroupByItemResolutionNode

/**
 * A path inside the group-by-item resolution DAG.
 *
 * Port of `metricflow_semantics.query.group_by_item.resolution_path.MetricFlowQueryResolutionPath`.
 *
 * Resolution paths track the chain of resolution nodes that produced (or
 * failed to produce) a given spec. Issues carry the path so error messages
 * can report the parent metric → input metric → query nesting.
 */
data class MetricFlowQueryResolutionPath(
    val resolutionPathNodes: List<GroupByItemResolutionNode>,
) : PathPrefixable<MetricFlowQueryResolutionPath> {

    /** The last node in this path. Throws if the path is empty. */
    val lastItem: GroupByItemResolutionNode
        get() = resolutionPathNodes.last()

    /**
     * Human-readable rendering of the path used by error messages.
     *
     * Mirrors Python's indented `[Resolve X] -> [Resolve Y]` format with a
     * fixed 80-char truncation.
     */
    val uiDescription: String
        get() {
            if (resolutionPathNodes.isEmpty()) return "[Empty Path]"
            val maxLineLength = 80
            val lines = mutableListOf<String>()
            for ((i, node) in resolutionPathNodes.withIndex()) {
                val indentPrefix = if (i == 0) "" else "  ".repeat(i) + "-> "
                val description = node.uiDescription
                val raw = "$indentPrefix[Resolve $description]"
                if (raw.length > maxLineLength) {
                    val ellipsis = "...)"
                    val shrink = raw.length - maxLineLength + ellipsis.length
                    val shortDescription = description.take(maxOf(1, description.length - shrink))
                    lines += "$indentPrefix[Resolve ${shortDescription + ellipsis}]"
                } else {
                    lines += raw
                }
            }
            return lines.joinToString("\n")
        }

    override fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): MetricFlowQueryResolutionPath =
        MetricFlowQueryResolutionPath(pathPrefix.resolutionPathNodes + resolutionPathNodes)

    override fun toString(): String = buildString {
        append(this@MetricFlowQueryResolutionPath::class.simpleName)
        append('(')
        append(resolutionPathNodes.joinToString(", ") { it.toString() })
        append(')')
    }

    companion object {
        /** The empty path. */
        val EMPTY: MetricFlowQueryResolutionPath = MetricFlowQueryResolutionPath(emptyList())

        /** Single-element path for [node]. */
        fun fromPathItem(node: GroupByItemResolutionNode): MetricFlowQueryResolutionPath =
            MetricFlowQueryResolutionPath(listOf(node))
    }
}
