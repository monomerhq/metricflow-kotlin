package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Pre-computed nodes used by the dataflow plan builder as root building blocks.
 *
 * Port of `metricflow.dataflow.builder.source_node.SourceNodeSet`.
 *
 * The components in this set do not need to be regenerated per-query for a given manifest:
 *
 * - Semantic models without simple-metric inputs are 1:1-mapped to a [ReadSqlSourceNode] used
 *   in [sourceNodesForMetricQueries]. Semantic models containing simple-metric inputs are
 *   mapped to one [MetricTimeDimensionTransformNode] per distinct aggregation-time-dimension.
 * - All semantic models are 1:1-mapped to a [ReadSqlSourceNode] in
 *   [sourceNodesForGroupByItemQueries] (used as candidate join nodes when satisfying linkable
 *   specs).
 * - Time spines feed two parallel maps: [timeSpineReadNodes] (raw reads) and
 *   [timeSpineMetricTimeNodes] (wrapped with a metric-time transform).
 */
data class SourceNodeSet(
    val sourceNodesForMetricQueries: List<DataflowPlanNode>,
    val sourceNodesForGroupByItemQueries: List<DataflowPlanNode>,
    val timeSpineReadNodes: Map<TimeGranularity, ReadSqlSourceNode>,
    val timeSpineMetricTimeNodes: Map<TimeGranularity, MetricTimeDimensionTransformNode>,
) {

    /** All nodes used by either metric-query or no-metric (time-spine) planning. */
    val allNodes: List<DataflowPlanNode>
        get() = sourceNodesForMetricQueries +
            sourceNodesForGroupByItemQueries +
            timeSpineMetricTimeNodesList

    /** Time spine metric-time nodes as a list, preserving insertion order. */
    val timeSpineMetricTimeNodesList: List<MetricTimeDimensionTransformNode>
        get() = timeSpineMetricTimeNodes.values.toList()
}
