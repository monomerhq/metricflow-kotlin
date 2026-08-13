package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.sql.plan.nodes.SqlSelectStatementNode

/**
 * A [SqlDataSet] backed by a specific semantic model. Port of
 * `metricflow.dataset.semantic_model_adapter.SemanticModelDataSet`.
 *
 * The only difference from [SqlDataSet] is that the [semanticModelReference] returns a non-null
 * value — the W7a manifest object lookup uses this to map dataflow source nodes back to the
 * semantic model they read from.
 */
class SemanticModelDataSet(
    override val semanticModelReference: SemanticModelReference,
    instanceSet: InstanceSet,
    sqlSelectNode: SqlSelectStatementNode,
) : SqlDataSet(instanceSet, sqlSelectNode) {

    override fun toString(): String =
        "${this::class.simpleName}(${semanticModelReference.semanticModelName})"
}
