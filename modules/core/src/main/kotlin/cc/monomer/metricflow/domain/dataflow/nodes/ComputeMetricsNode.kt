package cc.monomer.metricflow.domain.dataflow.nodes

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.DataflowPlanNodeVisitor
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * Computes metrics from input simple-metric inputs. Dimensions and entities pass through unchanged.
 *
 * Port of `metricflow.dataflow.nodes.compute_metrics.ComputeMetricsNode`.
 *
 * @property computedMetricSpecs Metrics that this node should compute.
 * @property passthroughMetricSpecs Metrics that should be passed unchanged from input to output.
 * @property outputGroupByMetricInstances If `true`, output computed metrics as
 *   `GroupByMetricInstance`s instead of `MetricInstance`s. Used when building the dataflow plan
 *   for a group-by source node.
 */
class ComputeMetricsNode(
    parentNode: DataflowPlanNode,
    val computedMetricSpecs: List<MetricSpec>,
    val passthroughMetricSpecs: List<MetricSpec>,
    val outputGroupByMetricInstances: Boolean,
    private val aggregatedToElementsList: List<LinkableInstanceSpec>,
) : DataflowPlanNode(parentNodes = listOf(parentNode)) {

    val parentNode: DataflowPlanNode get() = parentNodes[0]

    /** Concatenation of [computedMetricSpecs] + [passthroughMetricSpecs]. */
    val metricSpecs: List<MetricSpec> get() = computedMetricSpecs + passthroughMetricSpecs

    override val aggregatedToElements: Set<LinkableInstanceSpec>
        get() = aggregatedToElementsList.toSet()

    override val description: String get() = "Compute Metrics via Expressions"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_COMPUTE_METRICS_ID_PREFIX

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(super.displayedProperties)
            for (spec in computedMetricSpecs) add(DisplayedProperty("metric_spec", spec))
            for (spec in passthroughMetricSpecs) add(DisplayedProperty("metric_spec", spec))
            if (outputGroupByMetricInstances) {
                add(DisplayedProperty("output_group_by_metric_instances", outputGroupByMetricInstances))
            }
        }

    override fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R =
        visitor.visitComputeMetricsNode(this)

    override fun functionallyIdentical(other: DataflowPlanNode): Boolean {
        if (other !is ComputeMetricsNode) return false
        return other.computedMetricSpecs == computedMetricSpecs &&
            other.passthroughMetricSpecs == passthroughMetricSpecs &&
            other.aggregatedToElements == aggregatedToElements &&
            other.outputGroupByMetricInstances == outputGroupByMetricInstances
    }

    /**
     * Check whether two `ComputeMetricsNode`s can be combined into one. Used by the W9b
     * `ComputeMetricsBranchCombiner`.
     *
     * Returns `(true, "")` when combinable, `(false, reason)` otherwise.
     */
    fun canCombine(other: ComputeMetricsNode): Pair<Boolean, String> {
        if (other.aggregatedToElements != aggregatedToElements) {
            return false to "nodes are aggregated to different elements"
        }
        if (other.outputGroupByMetricInstances != outputGroupByMetricInstances) {
            return false to "one node is a group by metric source node"
        }
        val aliasToMetricSpec = metricSpecs.filter { it.alias != null }.associateBy { it.alias!! }
        for (spec in other.computedMetricSpecs) {
            val alias = spec.alias ?: continue
            val existing = aliasToMetricSpec[alias] ?: continue
            if (existing != spec) {
                return false to "Alias '$alias' is defined in both nodes but it refers to different things in each of them"
            }
        }
        return true to ""
    }

    override fun withNewParents(newParentNodes: List<DataflowPlanNode>): ComputeMetricsNode {
        check(newParentNodes.size == 1) {
            "ComputeMetricsNode expects exactly one parent. Got: ${newParentNodes.size}"
        }
        return ComputeMetricsNode(
            parentNode = newParentNodes[0],
            computedMetricSpecs = computedMetricSpecs,
            passthroughMetricSpecs = passthroughMetricSpecs,
            outputGroupByMetricInstances = outputGroupByMetricInstances,
            aggregatedToElementsList = aggregatedToElementsList,
        )
    }

    companion object {
        /**
         * Construct a `ComputeMetricsNode`. Matches Python's `create` factory — accepts
         * iterables, normalizes to lists internally.
         */
        fun create(
            parentNode: DataflowPlanNode,
            computedMetricSpecs: Iterable<MetricSpec>,
            passthroughMetricSpecs: Iterable<MetricSpec>,
            aggregatedToElements: Set<LinkableInstanceSpec>,
            outputGroupByMetricInstances: Boolean,
        ): ComputeMetricsNode = ComputeMetricsNode(
            parentNode = parentNode,
            computedMetricSpecs = computedMetricSpecs.toList(),
            passthroughMetricSpecs = passthroughMetricSpecs.toList(),
            outputGroupByMetricInstances = outputGroupByMetricInstances,
            aggregatedToElementsList = aggregatedToElements.toList(),
        )
    }
}
