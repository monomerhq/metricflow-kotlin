package cc.monomer.metricflow.domain.metric_evaluation.plan

import cc.monomer.metricflow.common.dag.SequentialId
import cc.monomer.metricflow.common.dag.SequentialIdGenerator
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.graph.MetricFlowGraphNode
import cc.monomer.metricflow.common.graph.MetricFlowGraphNodeDescriptor
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.spec.MetricSpec

/**
 * A node in the [MetricEvaluationPlan] representing a query for a specific set
 * of metrics.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.MetricQueryNode`.
 *
 * The dependencies of a node (i.e. its source nodes in the graph) describe
 * the subqueries needed to compute its outputs. The outputs of a node
 * ([outputMetricSpecs]) describe what the corresponding SQL query produces —
 * both *computed* metrics (e.g. a derived metric definition's output) and
 * *passthrough* metrics (input metrics carried through unchanged so a parent
 * node can reuse them).
 */
sealed class MetricQueryNode : MetricFlowGraphNode() {

    /** Unique identifier for this node within the plan. */
    abstract val nodeId: SequentialId

    /** Query properties (group-by items + predicate pushdown) for this node. */
    abstract val queryProperties: MetricQueryPropertySet

    /**
     * Return a copy of this node with outputs filtered to [allowedSpecs].
     *
     * Throws if every output would be removed — callers must always retain at
     * least one spec to keep the graph well-formed.
     */
    abstract fun pruned(allowedSpecs: Set<MetricSpec>): MetricQueryNode

    /** Specs for the metrics output by this node (both computed and passthrough). */
    abstract val outputMetricSpecs: OrderedSet<MetricSpec>

    /** Same as [outputMetricSpecs] but as [MetricQueryElement]s. */
    val outputQueryElements: OrderedSet<MetricQueryElement>
        get() {
            val set = FrozenOrderedSet(
                outputMetricSpecs.map { createOutputQueryElement(it) },
            )
            return set
        }

    /** Dispatch to a type-specific visitor implementation. */
    abstract fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R

    override val nodeDescriptor: MetricFlowGraphNodeDescriptor
        get() = MetricFlowGraphNodeDescriptor(nodeName = nodeId.strValue, clusterName = null)

    /** Build a [MetricQueryElement] for one of this node's output metric specs. */
    protected fun createOutputQueryElement(metricSpec: MetricSpec): MetricQueryElement =
        MetricQueryElement.create(
            metricSpec = metricSpec,
            groupByItemSpecs = queryProperties.groupByItemSpecs,
            predicatePushdownState = queryProperties.predicatePushdownState,
        )
}

/**
 * Visitor over the [MetricQueryNode] sealed family.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.MetricQueryNodeVisitor`.
 *
 * Kotlin's `when (node) { ... }` over a sealed type is exhaustively checked
 * by the compiler; the visitor is retained so call sites that prefer
 * dispatch-style polymorphism (matching the Python idiom) don't need to
 * inline a `when` every time.
 */
interface MetricQueryNodeVisitor<R> {
    fun visitSimpleMetricsQueryNode(node: SimpleMetricsQueryNode): R
    fun visitCumulativeMetricQueryNode(node: CumulativeMetricQueryNode): R
    fun visitConversionMetricQueryNode(node: ConversionMetricQueryNode): R
    fun visitDerivedMetricsQueryNode(node: DerivedMetricsQueryNode): R
    fun visitTopLevelQueryNode(node: TopLevelQueryNode): R
}

/**
 * Common base for nodes that compute *base* metrics — i.e. metrics whose
 * evaluation does not depend on other metric queries.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.BaseMetricQueryNode`.
 */
sealed class BaseMetricQueryNode : MetricQueryNode() {
    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = FrozenOrderedSet(listOf(BaseMetricQueryLabel))
}

/**
 * A query node that reads from a single semantic model and computes one or
 * more simple metrics defined on that model.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.SimpleMetricsQueryNode`.
 *
 * Simple metric specs that share a modifier (i.e. same filter / alias /
 * offset) are grouped into a single node so the planner can emit a single
 * SQL query.
 */
class SimpleMetricsQueryNode private constructor(
    override val nodeId: SequentialId,
    override val queryProperties: MetricQueryPropertySet,
    val modelId: SemanticModelId,
    val metricSpecs: List<MetricSpec>,
) : BaseMetricQueryNode() {

    init {
        val modifiers = metricSpecs.map { it.metricModifier }.toSet()
        check(modifiers.size <= 1) {
            "All metric specs should map to exactly one modifier due to SQL query " +
                "limitations (e.g. each unique filter requires a separate SQL query " +
                "with the appropriate `WHERE` clause). " +
                "metricSpecs=$metricSpecs"
        }
    }

    override val outputMetricSpecs: OrderedSet<MetricSpec>
        get() = FrozenOrderedSet(metricSpecs)

    override fun pruned(allowedSpecs: Set<MetricSpec>): SimpleMetricsQueryNode {
        val filtered = metricSpecs.filter { it in allowedSpecs }
        if (filtered.isEmpty()) {
            throw IllegalStateException(
                "Can't return a copy if all metric specs are filtered out " +
                    "(metricSpecs=$metricSpecs, allowedSpecs=$allowedSpecs)",
            )
        }
        if (filtered.size == metricSpecs.size) return this
        return create(
            modelId = modelId,
            metricSpecs = filtered,
            queryProperties = queryProperties,
        )
    }

    override fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R =
        visitor.visitSimpleMetricsQueryNode(this)

    override fun equals(other: Any?): Boolean =
        other is SimpleMetricsQueryNode && nodeId == other.nodeId &&
            queryProperties == other.queryProperties &&
            modelId == other.modelId &&
            metricSpecs == other.metricSpecs

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + queryProperties.hashCode()
        result = 31 * result + modelId.hashCode()
        result = 31 * result + metricSpecs.hashCode()
        return result
    }

    override fun toString(): String =
        "SimpleMetricsQueryNode(nodeId=${nodeId.strValue}, modelId=$modelId, metricSpecs=$metricSpecs)"

    companion object {
        /** Factory mirroring Python `SimpleMetricsQueryNode.create`. */
        fun create(
            modelId: SemanticModelId,
            metricSpecs: Iterable<MetricSpec>,
            queryProperties: MetricQueryPropertySet,
        ): SimpleMetricsQueryNode = SimpleMetricsQueryNode(
            nodeId = SequentialIdGenerator.createNextId(
                StaticIdPrefix.METRIC_EVALUATION_NODE__SIMPLE_METRICS_QUERY,
            ),
            queryProperties = queryProperties,
            modelId = modelId,
            metricSpecs = metricSpecs.toList(),
        )
    }
}

/**
 * A query node that computes one cumulative metric.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.CumulativeMetricQueryNode`.
 *
 * Cumulative metrics always live in their own node — they require a
 * time-spine join that can't be shared with other metrics.
 */
class CumulativeMetricQueryNode private constructor(
    override val nodeId: SequentialId,
    override val queryProperties: MetricQueryPropertySet,
    val metricSpec: MetricSpec,
) : BaseMetricQueryNode() {

    override val outputMetricSpecs: OrderedSet<MetricSpec>
        get() = FrozenOrderedSet(listOf(metricSpec))

    override fun pruned(allowedSpecs: Set<MetricSpec>): CumulativeMetricQueryNode {
        if (metricSpec !in allowedSpecs) {
            throw IllegalStateException(
                "Can't return a copy if all metric specs are filtered out " +
                    "(metricSpec=$metricSpec, allowedSpecs=$allowedSpecs)",
            )
        }
        return this
    }

    override fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R =
        visitor.visitCumulativeMetricQueryNode(this)

    override fun equals(other: Any?): Boolean =
        other is CumulativeMetricQueryNode && nodeId == other.nodeId &&
            queryProperties == other.queryProperties &&
            metricSpec == other.metricSpec

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + queryProperties.hashCode()
        result = 31 * result + metricSpec.hashCode()
        return result
    }

    override fun toString(): String =
        "CumulativeMetricQueryNode(nodeId=${nodeId.strValue}, metricSpec=$metricSpec)"

    companion object {
        /** Factory mirroring Python `CumulativeMetricQueryNode.create`. */
        fun create(
            metricSpec: MetricSpec,
            queryProperties: MetricQueryPropertySet,
        ): CumulativeMetricQueryNode = CumulativeMetricQueryNode(
            nodeId = SequentialIdGenerator.createNextId(
                StaticIdPrefix.METRIC_EVALUATION_NODE__CUMULATIVE_METRIC_QUERY,
            ),
            queryProperties = queryProperties,
            metricSpec = metricSpec,
        )
    }
}

/**
 * A query node that computes one conversion metric.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.ConversionMetricQueryNode`.
 *
 * Each conversion metric is computed in its own SQL query for simplicity.
 */
class ConversionMetricQueryNode private constructor(
    override val nodeId: SequentialId,
    override val queryProperties: MetricQueryPropertySet,
    val metricSpec: MetricSpec,
) : BaseMetricQueryNode() {

    override val outputMetricSpecs: OrderedSet<MetricSpec>
        get() = FrozenOrderedSet(listOf(metricSpec))

    override fun pruned(allowedSpecs: Set<MetricSpec>): ConversionMetricQueryNode {
        if (metricSpec !in allowedSpecs) {
            throw IllegalStateException(
                "Can't return a copy if all metric specs are filtered out " +
                    "(metricSpec=$metricSpec, allowedSpecs=$allowedSpecs)",
            )
        }
        return this
    }

    override fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R =
        visitor.visitConversionMetricQueryNode(this)

    override fun equals(other: Any?): Boolean =
        other is ConversionMetricQueryNode && nodeId == other.nodeId &&
            queryProperties == other.queryProperties &&
            metricSpec == other.metricSpec

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + queryProperties.hashCode()
        result = 31 * result + metricSpec.hashCode()
        return result
    }

    override fun toString(): String =
        "ConversionMetricQueryNode(nodeId=${nodeId.strValue}, metricSpec=$metricSpec)"

    companion object {
        /** Factory mirroring Python `ConversionMetricQueryNode.create`. */
        fun create(
            metricSpec: MetricSpec,
            queryProperties: MetricQueryPropertySet,
        ): ConversionMetricQueryNode = ConversionMetricQueryNode(
            nodeId = SequentialIdGenerator.createNextId(
                StaticIdPrefix.METRIC_EVALUATION_NODE__CONVERSION_METRIC_QUERY,
            ),
            queryProperties = queryProperties,
            metricSpec = metricSpec,
        )
    }
}

/**
 * A query node that computes one or more derived (or ratio) metrics from input
 * metric queries.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.DerivedMetricsQueryNode`.
 *
 * Derived metrics can additionally pass through their inputs unchanged when
 * those inputs are unaliased — this lets a parent that needs both the derived
 * metric and one of its inputs share a single subquery.
 */
class DerivedMetricsQueryNode private constructor(
    override val nodeId: SequentialId,
    override val queryProperties: MetricQueryPropertySet,
    val computedMetricSpecs: List<MetricSpec>,
    val passthroughMetricSpecs: List<MetricSpec>,
) : MetricQueryNode() {

    init {
        check(computedMetricSpecs.isNotEmpty()) {
            "A derived metric query node must compute at least one metric"
        }
        for (passthroughSpec in passthroughMetricSpecs) {
            check(passthroughSpec.metricModifier.alias == null) {
                "Passthrough metrics with an alias are not supported to simplify " +
                    "alias-collision handling: $passthroughSpec"
            }
        }
    }

    override val outputMetricSpecs: OrderedSet<MetricSpec>
        get() = FrozenOrderedSet(computedMetricSpecs + passthroughMetricSpecs)

    override fun pruned(allowedSpecs: Set<MetricSpec>): DerivedMetricsQueryNode {
        val filteredComputed = computedMetricSpecs.filter { it in allowedSpecs }
        if (filteredComputed.isEmpty()) {
            throw IllegalStateException(
                "Can't return a copy if all computed metric specs are filtered out " +
                    "(computedMetricSpecs=$computedMetricSpecs, allowedSpecs=$allowedSpecs)",
            )
        }

        val filteredPassthrough = passthroughMetricSpecs.filter { it in allowedSpecs }
        if (passthroughMetricSpecs == filteredPassthrough && computedMetricSpecs == filteredComputed) {
            return this
        }

        return create(
            computedMetricSpecs = filteredComputed,
            passthroughMetricSpecs = filteredPassthrough,
            queryProperties = queryProperties,
        )
    }

    override fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R =
        visitor.visitDerivedMetricsQueryNode(this)

    override fun equals(other: Any?): Boolean =
        other is DerivedMetricsQueryNode && nodeId == other.nodeId &&
            queryProperties == other.queryProperties &&
            computedMetricSpecs == other.computedMetricSpecs &&
            passthroughMetricSpecs == other.passthroughMetricSpecs

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + queryProperties.hashCode()
        result = 31 * result + computedMetricSpecs.hashCode()
        result = 31 * result + passthroughMetricSpecs.hashCode()
        return result
    }

    override fun toString(): String =
        "DerivedMetricsQueryNode(nodeId=${nodeId.strValue}, " +
            "computedMetricSpecs=$computedMetricSpecs, passthroughMetricSpecs=$passthroughMetricSpecs)"

    companion object {
        /** Factory mirroring Python `DerivedMetricsQueryNode.create`. */
        fun create(
            computedMetricSpecs: Iterable<MetricSpec>,
            passthroughMetricSpecs: Iterable<MetricSpec>,
            queryProperties: MetricQueryPropertySet,
        ): DerivedMetricsQueryNode = DerivedMetricsQueryNode(
            nodeId = SequentialIdGenerator.createNextId(
                StaticIdPrefix.METRIC_EVALUATION_NODE__DERIVED_METRIC_QUERY,
            ),
            queryProperties = queryProperties,
            computedMetricSpecs = computedMetricSpecs.toList(),
            passthroughMetricSpecs = passthroughMetricSpecs.toList(),
        )
    }
}

/**
 * The root node of a [MetricEvaluationPlan] — the metrics requested at the top
 * of the query.
 *
 * Port of `metricflow.metric_evaluation.plan.me_nodes.TopLevelQueryNode`.
 *
 * This node performs no computation itself; it exists to give the dependency
 * traversal a single entry point.
 */
class TopLevelQueryNode private constructor(
    override val nodeId: SequentialId,
    override val queryProperties: MetricQueryPropertySet,
    val passthroughMetricSpecs: List<MetricSpec>,
) : MetricQueryNode() {

    init {
        if (passthroughMetricSpecs.isEmpty()) {
            throw MetricFlowInternalError("A top-level query must have at least one metric")
        }
    }

    override val labels: OrderedSet<MetricFlowGraphLabel>
        get() = FrozenOrderedSet(listOf(TopLevelQueryLabel))

    override val outputMetricSpecs: OrderedSet<MetricSpec>
        get() = FrozenOrderedSet(passthroughMetricSpecs)

    override fun pruned(allowedSpecs: Set<MetricSpec>): TopLevelQueryNode {
        val filtered = passthroughMetricSpecs.filter { it in allowedSpecs }
        if (filtered.isEmpty()) {
            throw IllegalStateException(
                "Can't return a copy if all metric specs are filtered out " +
                    "(passthroughMetricSpecs=$passthroughMetricSpecs, allowedSpecs=$allowedSpecs)",
            )
        }
        if (filtered == passthroughMetricSpecs) return this
        return create(passthroughMetricSpecs = filtered, queryProperties = queryProperties)
    }

    override fun <R> accept(visitor: MetricQueryNodeVisitor<R>): R =
        visitor.visitTopLevelQueryNode(this)

    override fun equals(other: Any?): Boolean =
        other is TopLevelQueryNode && nodeId == other.nodeId &&
            queryProperties == other.queryProperties &&
            passthroughMetricSpecs == other.passthroughMetricSpecs

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + queryProperties.hashCode()
        result = 31 * result + passthroughMetricSpecs.hashCode()
        return result
    }

    override fun toString(): String =
        "TopLevelQueryNode(nodeId=${nodeId.strValue}, passthroughMetricSpecs=$passthroughMetricSpecs)"

    companion object {
        /** Factory mirroring Python `TopLevelQueryNode.create`. */
        fun create(
            passthroughMetricSpecs: Iterable<MetricSpec>,
            queryProperties: MetricQueryPropertySet,
        ): TopLevelQueryNode = TopLevelQueryNode(
            nodeId = SequentialIdGenerator.createNextId(
                StaticIdPrefix.METRIC_EVALUATION_NODE__TOP_LEVEL_QUERY,
            ),
            queryProperties = queryProperties,
            passthroughMetricSpecs = passthroughMetricSpecs.toList(),
        )
    }
}
