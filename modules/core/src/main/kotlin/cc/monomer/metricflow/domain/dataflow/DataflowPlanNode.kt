package cc.monomer.metricflow.domain.dataflow

import cc.monomer.metricflow.common.dag.DagNode
import cc.monomer.metricflow.common.dag.DagId
import cc.monomer.metricflow.common.dag.StaticIdPrefix
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec

/**
 * A node in the graph representation of the dataflow.
 *
 * Port of `metricflow.dataflow.dataflow_plan.DataflowPlanNode`.
 *
 * Each node in the graph performs an operation on the data that comes from the parent nodes; the
 * result is passed to the child nodes. Data flows from source nodes (typically
 * [cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode]) toward sink nodes
 * (e.g. [cc.monomer.metricflow.domain.dataflow.nodes.WriteToResultTableNode]).
 *
 * **Why an abstract class instead of `sealed`?** The 23 concrete variants live in a
 * sub-package ([nodes]) so a single file does not become unmanageably large. Kotlin 2 does not
 * allow `sealed` declarations to have subclasses in sibling sub-packages, so we instead enforce
 * closed-set discipline through the [DataflowPlanNodeVisitor] interface — every variant has a
 * matching `visit*` method and the compiler complains if a visitor implementation misses one.
 *
 * **Identity equality.** Each node carries an auto-minted [nodeId] from [DagNode]. Two distinct
 * Kotlin objects always compare unequal even with identical fields. Use [functionallyIdentical]
 * (port of Python `functionally_identical`) when you want field-by-field "semantic" equality
 * ignoring `parent_nodes` and `node_id`.
 *
 * **Mutation.** Nodes are immutable. To build a derived plan use [withNewParents] to swap
 * parents while preserving all other fields. Concrete subclasses validate parent arity in
 * their override.
 */
abstract class DataflowPlanNode(parentNodes: List<DataflowPlanNode>) :
    DagNode<DataflowPlanNode>(parentNodes) {

    /**
     * Returns the semantic model serving as the direct input for this node, if one exists.
     *
     * Port of Python `DataflowPlanNode._input_semantic_model` (default `None`). Overridden by
     * source nodes such as `ReadSqlSourceNode` that wrap a [SemanticModelReference] in their
     * data set.
     */
    open val inputSemanticModel: SemanticModelReference? get() = null

    /**
     * If this node has been aggregated to a set of linkable elements, return that set.
     *
     * Port of Python `DataflowPlanNode.aggregated_to_elements` (default empty). Overridden by
     * [cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode] which carries
     * the aggregation set forward to downstream nodes.
     */
    open val aggregatedToElements: Set<LinkableInstanceSpec> get() = emptySet()

    /** Dispatch this node onto a [DataflowPlanNodeVisitor]. Port of `accept`. */
    abstract fun <R> accept(visitor: DataflowPlanNodeVisitor<R>): R

    /**
     * Returns `true` if `other` performs the same operation as this node (i.e. all parameters
     * aside from `parent_nodes` are equal).
     *
     * Port of Python `functionally_identical`. Used by the dataflow optimizer to detect when
     * two sub-branches can be combined.
     */
    abstract fun functionallyIdentical(other: DataflowPlanNode): Boolean

    /**
     * Return a copy of this node with new parents. The new parents must be of the appropriate
     * type and order as required by the concrete subclass — callers are responsible for
     * matching the original arity.
     *
     * Port of Python `with_new_parents`.
     */
    abstract fun withNewParents(newParentNodes: List<DataflowPlanNode>): DataflowPlanNode

    /**
     * Wrap this node as the sink of a new [DataflowPlan].
     *
     * Port of Python `DataflowPlanNode.as_plan`. Useful for properties that are defined at the
     * plan level (e.g. `nodeCount`, `sourceSemanticModels`) when the caller only holds a
     * sub-DAG handle.
     */
    fun asPlan(): DataflowPlan = DataflowPlan(
        renderNode = this,
        planId = DagId.fromIdPrefix(StaticIdPrefix.DATAFLOW_PLAN_SUBGRAPH_PREFIX),
    )
}
