package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.graph.MetricFlowGraphEdge
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStepProvider

/**
 * An edge in the semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/sg_interfaces.py::SemanticGraphEdge`
 * plus the concrete variants in `edges/sg_edges.py`.
 *
 * Every edge contributes a recipe step describing how the path's resolved
 * attribute state evolves at that point — typically a join to a model, an
 * entity-link addition, or a recipe-property update.
 */
sealed class SemanticGraphEdge(
    tailNode: SemanticGraphNode,
    headNode: SemanticGraphNode,
) : MetricFlowGraphEdge<SemanticGraphNode>(tailNode, headNode),
    AttributeRecipeStepProvider {

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            addAll(recipeStepToAppend.displayedProperties)
        }
}

/**
 * Edge that joins a model on the right side.
 *
 * Port of `JoinToModelEdge`.
 */
class JoinToModelEdge private constructor(
    tailNode: SemanticGraphNode,
    headNode: SemanticGraphNode,
    val rightModelId: SemanticModelId,
) : SemanticGraphEdge(tailNode, headNode) {

    override fun inverse(): SemanticGraphEdge = JoinToModelEdge(headNode, tailNode, rightModelId)

    override val recipeStepToAppend: AttributeRecipeStep
        get() = AttributeRecipeStep.EMPTY.copy(addModelJoin = rightModelId)

    override fun equals(other: Any?): Boolean = other is JoinToModelEdge &&
        tailNode == other.tailNode &&
        headNode == other.headNode &&
        rightModelId == other.rightModelId

    override fun hashCode(): Int {
        var h = tailNode.hashCode()
        h = 31 * h + headNode.hashCode()
        h = 31 * h + rightModelId.hashCode()
        return h
    }

    companion object {
        fun create(
            tailNode: SemanticGraphNode,
            headNode: SemanticGraphNode,
            rightModelId: SemanticModelId,
        ): JoinToModelEdge = JoinToModelEdge(tailNode, headNode, rightModelId)
    }
}

/**
 * Edge from a metric node to the inputs that define it.
 *
 * Port of `MetricDefinitionEdge`. Carries `additionalLabels` (e.g.
 * [cc.monomer.metricflow.domain.semantic_graph.edge.CumulativeMetricLabel])
 * and a recipe step describing how the edge affects the attribute resolution.
 */
class MetricDefinitionEdge private constructor(
    tailNode: SemanticGraphNode,
    headNode: SemanticGraphNode,
    val additionalLabels: OrderedSet<MetricFlowGraphLabel>,
    val recipeStep: AttributeRecipeStep,
) : SemanticGraphEdge(tailNode, headNode) {

    override fun inverse(): SemanticGraphEdge = MetricDefinitionEdge(headNode, tailNode, additionalLabels, recipeStep)

    override val recipeStepToAppend: AttributeRecipeStep get() = recipeStep

    override val labels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet(additionalLabels)

    override fun equals(other: Any?): Boolean = other is MetricDefinitionEdge &&
        tailNode == other.tailNode &&
        headNode == other.headNode

    override fun hashCode(): Int = 31 * tailNode.hashCode() + headNode.hashCode()

    companion object {
        fun create(
            tailNode: SemanticGraphNode,
            headNode: SemanticGraphNode,
            additionalLabels: OrderedSet<MetricFlowGraphLabel>?,
            recipeStep: AttributeRecipeStep?,
        ): MetricDefinitionEdge = MetricDefinitionEdge(
            tailNode = tailNode,
            headNode = headNode,
            additionalLabels = additionalLabels ?: FrozenOrderedSet(),
            recipeStep = recipeStep ?: AttributeRecipeStep.EMPTY,
        )
    }
}

/**
 * Edge between two entity nodes.
 *
 * Port of `EntityRelationshipEdge`.
 */
class EntityRelationshipEdge private constructor(
    tailNode: SemanticGraphNode,
    headNode: SemanticGraphNode,
    private val recipeUpdate: AttributeRecipeStep,
) : SemanticGraphEdge(tailNode, headNode) {

    override fun inverse(): SemanticGraphEdge = EntityRelationshipEdge(headNode, tailNode, recipeUpdate)

    override val recipeStepToAppend: AttributeRecipeStep get() = recipeUpdate

    override fun equals(other: Any?): Boolean = other is EntityRelationshipEdge &&
        tailNode == other.tailNode &&
        headNode == other.headNode &&
        recipeUpdate == other.recipeUpdate

    override fun hashCode(): Int {
        var h = tailNode.hashCode()
        h = 31 * h + headNode.hashCode()
        h = 31 * h + recipeUpdate.hashCode()
        return h
    }

    companion object {
        fun create(
            tailNode: SemanticGraphNode,
            headNode: SemanticGraphNode,
            recipeUpdate: AttributeRecipeStep,
        ): EntityRelationshipEdge = EntityRelationshipEdge(tailNode, headNode, recipeUpdate)
    }
}

/**
 * Edge from an entity node to an attribute node.
 *
 * Port of `EntityAttributeEdge`.
 */
class EntityAttributeEdge private constructor(
    tailNode: SemanticGraphNode,
    headNode: SemanticGraphNode,
    private val recipeStep: AttributeRecipeStep,
) : SemanticGraphEdge(tailNode, headNode) {

    override fun inverse(): SemanticGraphEdge = EntityAttributeEdge(headNode, tailNode, recipeStep)

    override val recipeStepToAppend: AttributeRecipeStep get() = recipeStep

    override fun equals(other: Any?): Boolean = other is EntityAttributeEdge &&
        tailNode == other.tailNode &&
        headNode == other.headNode &&
        recipeStep == other.recipeStep

    override fun hashCode(): Int {
        var h = tailNode.hashCode()
        h = 31 * h + headNode.hashCode()
        h = 31 * h + recipeStep.hashCode()
        return h
    }

    companion object {
        fun create(
            tailNode: SemanticGraphNode,
            headNode: SemanticGraphNode,
            recipeStep: AttributeRecipeStep?,
        ): EntityAttributeEdge = EntityAttributeEdge(
            tailNode = tailNode,
            headNode = headNode,
            recipeStep = recipeStep ?: AttributeRecipeStep.EMPTY,
        )
    }
}
