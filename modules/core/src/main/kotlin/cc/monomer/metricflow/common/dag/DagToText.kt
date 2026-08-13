package cc.monomer.metricflow.common.dag

import cc.monomer.metricflow.common.logging.mfPformat
import cc.monomer.metricflow.common.util.MF_INDENT_2_SPACE
import cc.monomer.metricflow.common.util.mfIndent

/** Default rendering width for [MetricFlowDagTextFormatter]. */
const val DAG_TEXT_DEFAULT_MAX_WIDTH: Int = 120

/** Default indent applied when nesting parent-node descriptions inside a child. */
const val DAG_TEXT_DEFAULT_PARENT_INDENT: String = "    "

/**
 * Converts a [DagNode] (and recursively its parents) to a text representation.
 *
 * Port of `metricflow_semantics.dag.dag_to_text.MetricFlowDagTextFormatter`.
 * The output mimics XML so snapshot tests can diff DAGs intuitively:
 *
 * ```
 * <SelectorNode>
 *     <!-- description = ... -->
 *     <ReadSqlSource>
 *         ...
 *     </ReadSqlSource>
 * </SelectorNode>
 * ```
 *
 * The original Python formatter has elaborate column-width tracking for
 * multi-line property values. We keep the simpler "one-line-or-skip" rule
 * because our snapshot tests don't depend on the exact column layout.
 */
class MetricFlowDagTextFormatter(
    private val maxWidth: Int,
    private val nodeParentIndentPrefix: String,
    private val valueIndentPrefix: String,
) {

    /** Default-argument overload (matches Python defaults). */
    constructor() : this(
        maxWidth = DAG_TEXT_DEFAULT_MAX_WIDTH,
        nodeParentIndentPrefix = DAG_TEXT_DEFAULT_PARENT_INDENT,
        valueIndentPrefix = MF_INDENT_2_SPACE,
    )

    /** Converts a single DAG component (rooted at [leafNode]) to text. */
    fun dagComponentToText(leafNode: DagNode<*>): String = recursivelyFormat(leafNode)

    /** Converts an entire DAG to text. */
    fun <T : DagNode<T>> dagToText(dag: MetricFlowDag<T>): String {
        val nodeClass = dag::class.simpleName ?: "MetricFlowDag"
        if (dag.sinkNodes.isEmpty()) return "<$nodeClass/>"
        val componentLines = dag.sinkNodes.map { dagComponentToText(it) }
        val lines = mutableListOf("<$nodeClass>")
        for (line in componentLines) lines.add(mfIndent(line, indentLevel = 1, indentPrefix = nodeParentIndentPrefix))
        lines.add("</$nodeClass>")
        return lines.joinToString("\n")
    }

    private fun recursivelyFormat(node: DagNode<*>): String {
        val parentLines = node.parentNodes.map { recursivelyFormat(it) }
        return formatToText(node, parentLines.joinToString("\n"))
    }

    private fun formatToText(node: DagNode<*>, innerContents: String?): String {
        val nodeClass = node::class.simpleName ?: "DagNode"
        val fields = node.displayedProperties.map { dp ->
            val value = mfPformat(dp.value)
            "<!-- ${dp.key} = $value -->"
        }
        if (fields.isEmpty() && innerContents.isNullOrEmpty()) return "<$nodeClass/>"

        val lines = mutableListOf<String>()
        lines.add("<$nodeClass>")
        for (line in fields) lines.add(mfIndent(line, indentLevel = 1, indentPrefix = nodeParentIndentPrefix))
        if (!innerContents.isNullOrEmpty()) {
            lines.add(mfIndent(innerContents, indentLevel = 1, indentPrefix = nodeParentIndentPrefix))
        }
        lines.add("</$nodeClass>")
        return lines.joinToString("\n")
    }
}
