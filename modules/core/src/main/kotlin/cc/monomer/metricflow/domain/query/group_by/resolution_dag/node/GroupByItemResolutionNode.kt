package cc.monomer.metricflow.domain.query.group_by.resolution_dag.node

import cc.monomer.metricflow.common.dag.DagNode
import cc.monomer.metricflow.common.dag.IdPrefix
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.InputMetricDefinitionLocation

/**
 * Base class for nodes in the group-by-item resolution DAG.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.resolution_nodes.base_node.GroupByItemResolutionNode`.
 *
 * The resolution DAG flows candidates from source nodes (one per simple
 * metric, or a single `NoMetricsGroupByItemSourceNode` for metric-less
 * queries) through any number of complex-metric nodes to a single sink
 * query node. Nodes intersect candidates from their parents and pass the
 * intersection down — an empty intersection becomes an issue.
 *
 * Concrete subclasses must:
 * - assign their own [cc.monomer.metricflow.common.dag.IdPrefix],
 * - implement [accept] for visitor dispatch,
 * - implement [uiDescription] for error rendering,
 * - implement [selfSet] for the `inclusive_ancestors` recursion.
 */
abstract class GroupByItemResolutionNode(
    parentNodes: List<GroupByItemResolutionNode>,
) : DagNode<GroupByItemResolutionNode>(parentNodes) {

    /** Visitor entry point. */
    abstract fun <OutputT> accept(visitor: GroupByItemResolutionNodeVisitor<OutputT>): OutputT

    /** Short string for the UI path renderer. */
    abstract val uiDescription: String

    /**
     * The single-node [GroupByItemResolutionNodeSet] containing `this`.
     *
     * Mirrors Python's `_self_set` — used to seed the
     * [inclusiveAncestors] computation.
     */
    protected abstract fun selfSet(): GroupByItemResolutionNodeSet

    /** This node together with every transitive ancestor. */
    fun inclusiveAncestors(): GroupByItemResolutionNodeSet {
        val ancestors = parentNodes.map { it.inclusiveAncestors() }
        return Mergeable.mergeIterable(
            items = listOf(selfSet()) + ancestors,
            empty = GroupByItemResolutionNodeSet.EMPTY,
        )
    }
}

/**
 * Visitor over the four concrete [GroupByItemResolutionNode] variants.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.resolution_nodes.base_node.GroupByItemResolutionNodeVisitor`.
 */
interface GroupByItemResolutionNodeVisitor<OutputT> {
    fun visitSimpleMetricNode(node: SimpleMetricGroupByItemSourceNode): OutputT
    fun visitNoMetricsQueryNode(node: NoMetricsGroupByItemSourceNode): OutputT
    fun visitComplexMetricNode(node: ComplexMetricGroupByItemResolutionNode): OutputT
    fun visitQueryNode(node: QueryGroupByItemResolutionNode): OutputT
}

/**
 * Typed bins of resolution-DAG nodes, partitioned by concrete variant.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.resolution_nodes.base_node.GroupByItemResolutionNodeSet`.
 *
 * Used to compute `inclusive_ancestors` without boxing every node into a
 * common bag. The [Mergeable] surface concatenates each bin pair-wise.
 */
data class GroupByItemResolutionNodeSet(
    val simpleMetricNodes: List<SimpleMetricGroupByItemSourceNode>,
    val noMetricsQueryNodes: List<NoMetricsGroupByItemSourceNode>,
    val complexMetricNodes: List<ComplexMetricGroupByItemResolutionNode>,
    val queryNodes: List<QueryGroupByItemResolutionNode>,
) : Mergeable<GroupByItemResolutionNodeSet> {

    override fun merge(other: GroupByItemResolutionNodeSet): GroupByItemResolutionNodeSet =
        GroupByItemResolutionNodeSet(
            simpleMetricNodes = simpleMetricNodes + other.simpleMetricNodes,
            noMetricsQueryNodes = noMetricsQueryNodes + other.noMetricsQueryNodes,
            complexMetricNodes = complexMetricNodes + other.complexMetricNodes,
            queryNodes = queryNodes + other.queryNodes,
        )

    companion object {
        /** Empty set used as the merge identity. */
        val EMPTY: GroupByItemResolutionNodeSet = GroupByItemResolutionNodeSet(
            simpleMetricNodes = emptyList(),
            noMetricsQueryNodes = emptyList(),
            complexMetricNodes = emptyList(),
            queryNodes = emptyList(),
        )
    }
}

/**
 * Resolution node for a leaf simple metric.
 *
 * Port of `SimpleMetricGroupByItemSourceNode`. Always has zero parents.
 */
class SimpleMetricGroupByItemSourceNode private constructor(
    val metricReference: MetricReference,
    val metricInputLocation: InputMetricDefinitionLocation?,
) : GroupByItemResolutionNode(parentNodes = emptyList()) {

    override val description: String
        get() = "Output group-by-items available for this simple metric."

    override fun idPrefix(): IdPrefix = StaticIdPrefix.SIMPLE_METRIC_GROUP_BY_ITEM_RESOLUTION_NODE

    override fun <OutputT> accept(visitor: GroupByItemResolutionNodeVisitor<OutputT>): OutputT =
        visitor.visitSimpleMetricNode(this)

    override val uiDescription: String
        get() = "SimpleMetric('${metricReference.elementName}')"

    override fun selfSet(): GroupByItemResolutionNodeSet =
        GroupByItemResolutionNodeSet.EMPTY.copy(simpleMetricNodes = listOf(this))

    companion object {
        fun create(
            simpleMetricReference: MetricReference,
            metricInputLocation: InputMetricDefinitionLocation?,
        ): SimpleMetricGroupByItemSourceNode = SimpleMetricGroupByItemSourceNode(
            metricReference = simpleMetricReference,
            metricInputLocation = metricInputLocation,
        )
    }
}

/**
 * Resolution node for queries that don't reference any metrics.
 *
 * Port of `NoMetricsGroupByItemSourceNode`. Always has zero parents.
 */
class NoMetricsGroupByItemSourceNode private constructor() :
    GroupByItemResolutionNode(parentNodes = emptyList()) {

    override val description: String
        get() = "Output the available group-by-items for a query without any metrics."

    override fun idPrefix(): IdPrefix = StaticIdPrefix.VALUES_GROUP_BY_ITEM_RESOLUTION_NODE

    override fun <OutputT> accept(visitor: GroupByItemResolutionNodeVisitor<OutputT>): OutputT =
        visitor.visitNoMetricsQueryNode(this)

    override val uiDescription: String
        get() = "${this::class.simpleName}()"

    override fun selfSet(): GroupByItemResolutionNodeSet =
        GroupByItemResolutionNodeSet.EMPTY.copy(noMetricsQueryNodes = listOf(this))

    companion object {
        fun create(): NoMetricsGroupByItemSourceNode = NoMetricsGroupByItemSourceNode()
    }
}

/**
 * Resolution node for a derived (complex) metric.
 *
 * Port of `ComplexMetricGroupByItemResolutionNode`. Has a non-empty parent
 * list whose entries are either simple-metric or complex-metric nodes.
 */
class ComplexMetricGroupByItemResolutionNode private constructor(
    val metricReference: MetricReference,
    val metricInputLocation: InputMetricDefinitionLocation?,
    parentNodes: List<GroupByItemResolutionNode>,
) : GroupByItemResolutionNode(parentNodes = parentNodes) {

    override val description: String
        get() = "Output group-by-items available for this metric."

    override fun idPrefix(): IdPrefix = StaticIdPrefix.METRIC_GROUP_BY_ITEM_RESOLUTION_NODE

    override fun <OutputT> accept(visitor: GroupByItemResolutionNodeVisitor<OutputT>): OutputT =
        visitor.visitComplexMetricNode(this)

    override val uiDescription: String
        get() = if (metricInputLocation == null) {
            "ComplexMetric('${metricReference.elementName}')"
        } else {
            "ComplexMetric('${metricReference.elementName}', input_metric_index=${metricInputLocation.inputMetricListIndex})"
        }

    override fun selfSet(): GroupByItemResolutionNodeSet =
        GroupByItemResolutionNodeSet.EMPTY.copy(complexMetricNodes = listOf(this))

    companion object {
        fun create(
            metricReference: MetricReference,
            metricInputLocation: InputMetricDefinitionLocation?,
            parentNodes: List<GroupByItemResolutionNode>,
        ): ComplexMetricGroupByItemResolutionNode = ComplexMetricGroupByItemResolutionNode(
            metricReference = metricReference,
            metricInputLocation = metricInputLocation,
            parentNodes = parentNodes,
        )
    }
}

/**
 * Sink node — represents the query itself.
 *
 * Port of `QueryGroupByItemResolutionNode`. The list of [metricsInQuery] and
 * the query-level [whereFilterIntersection] live on this node so the
 * resolver visitor can pull both at the final step.
 */
class QueryGroupByItemResolutionNode private constructor(
    parentNodes: List<GroupByItemResolutionNode>,
    val metricsInQuery: List<MetricReference>,
    val whereFilterIntersection: WhereFilterIntersection,
) : GroupByItemResolutionNode(parentNodes = parentNodes) {

    override val description: String
        get() = "Output the group-by items for query."

    override fun idPrefix(): IdPrefix = StaticIdPrefix.QUERY_GROUP_BY_ITEM_RESOLUTION_NODE

    override fun <OutputT> accept(visitor: GroupByItemResolutionNodeVisitor<OutputT>): OutputT =
        visitor.visitQueryNode(this)

    override val uiDescription: String
        get() = buildString {
            append("Query(metrics=[")
            append(metricsInQuery.joinToString(", ") { "'${it.elementName}'" })
            append("])")
        }

    override fun selfSet(): GroupByItemResolutionNodeSet =
        GroupByItemResolutionNodeSet.EMPTY.copy(queryNodes = listOf(this))

    companion object {
        fun create(
            parentNodes: List<GroupByItemResolutionNode>,
            metricsInQuery: List<MetricReference>,
            whereFilterIntersection: WhereFilterIntersection,
        ): QueryGroupByItemResolutionNode = QueryGroupByItemResolutionNode(
            parentNodes = parentNodes,
            metricsInQuery = metricsInQuery,
            whereFilterIntersection = whereFilterIntersection,
        )
    }
}
