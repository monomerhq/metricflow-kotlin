package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId

/**
 * The dunder-name parts of a recipe, indexed as a sequence of name elements.
 *
 * Port of `IndexedDunderName = AnyLengthTuple[str]`.
 */
typealias IndexedDunderName = List<String>

/**
 * The recipe for computing an attribute by following a path in the semantic
 * graph.
 *
 * Port of `metricflow_semantics/semantic_graph/attribute_resolution/attribute_recipe.py::AttributeRecipe`.
 *
 * As a pathfinder walks a path from a source node (typically a metric) to a
 * target attribute node it accumulates [AttributeRecipeStep]s. The accumulated
 * recipe describes the SQL-side query that should be generated to materialise
 * the attribute.
 */
data class AttributeRecipe(
    /** Dunder-name parts collected so far (e.g. `["metric_time", "day"]`). */
    val indexedDunderName: IndexedDunderName,
    /** Joined semantic-model IDs, in traversal order. */
    val joinedModelIds: List<SemanticModelId>,
    /** Properties of the resolved attribute. */
    val elementProperties: OrderedSet<GroupByItemProperty>,
    /** Entity links collected so far. */
    val entityLinkNames: List<String>,
    /** The resolved element type, or `null` if not yet known. */
    val elementType: LinkableElementType?,
    /** Source time-grain (matches the grain configured on a time dimension). */
    val sourceTimeGrain: TimeGranularity?,
    /** The attribute's time grain (or date_part-only grain). */
    val recipeTimeGrain: ExpandedTimeGranularity?,
    /** The attribute's date_part, if applicable. */
    val recipeDatePart: DatePart?,
) {

    /** The dunder-name elements as a `Set` for fast repeated-element checks. */
    val dunderNameElementsSet: Set<String> get() = indexedDunderName.toSet()

    /** The joined semantic-model IDs as a `Set` for fast repeated-model checks. */
    val joinedModelIdSet: Set<SemanticModelId> get() = joinedModelIds.toSet()

    /** The last model ID that was added to the join, or `null` if none. */
    val lastModelId: SemanticModelId? get() = joinedModelIds.lastOrNull()

    /** Add a step to the end of the recipe. */
    fun appendStep(recipeStep: AttributeRecipeStep): AttributeRecipe {
        var dunderNameElements = indexedDunderName
        if (recipeStep.addDunderNameElement != null) {
            dunderNameElements = dunderNameElements + recipeStep.addDunderNameElement
        }
        var entityLinks = entityLinkNames
        if (recipeStep.addEntityLink != null) {
            entityLinks = entityLinks + recipeStep.addEntityLink
        }
        var models = joinedModelIds
        if (recipeStep.addModelJoin != null) {
            models = models + recipeStep.addModelJoin
        }
        val newProperties: OrderedSet<GroupByItemProperty> =
            if (recipeStep.addProperties != null) {
                elementProperties.union(recipeStep.addProperties)
            } else {
                elementProperties
            }

        return AttributeRecipe(
            indexedDunderName = dunderNameElements,
            joinedModelIds = models,
            elementProperties = newProperties,
            entityLinkNames = entityLinks,
            elementType = recipeStep.setElementType ?: elementType,
            sourceTimeGrain = recipeStep.setSourceTimeGrain ?: sourceTimeGrain,
            recipeTimeGrain = recipeStep.setTimeGrainAccess ?: recipeTimeGrain,
            recipeDatePart = recipeStep.setDatePartAccess ?: recipeDatePart,
        )
    }

    /** Add a step to the beginning of the recipe. */
    fun pushStep(recipeStep: AttributeRecipeStep): AttributeRecipe {
        var dunderNameElements = indexedDunderName
        if (recipeStep.addDunderNameElement != null) {
            dunderNameElements = listOf(recipeStep.addDunderNameElement) + dunderNameElements
        }
        var entityLinks = entityLinkNames
        if (recipeStep.addEntityLink != null) {
            entityLinks = listOf(recipeStep.addEntityLink) + entityLinks
        }
        var models = joinedModelIds
        if (recipeStep.addModelJoin != null) {
            models = if (models.isEmpty()) listOf(recipeStep.addModelJoin) else listOf(recipeStep.addModelJoin) + models
        }
        val newProperties: OrderedSet<GroupByItemProperty> =
            if (recipeStep.addProperties != null) {
                FrozenOrderedSet(recipeStep.addProperties).union(elementProperties)
            } else {
                elementProperties
            }

        return AttributeRecipe(
            indexedDunderName = dunderNameElements,
            joinedModelIds = models,
            elementProperties = newProperties,
            entityLinkNames = entityLinks,
            elementType = elementType ?: recipeStep.setElementType,
            sourceTimeGrain = sourceTimeGrain ?: recipeStep.setSourceTimeGrain,
            recipeTimeGrain = recipeTimeGrain ?: recipeStep.setTimeGrainAccess,
            recipeDatePart = recipeDatePart ?: recipeStep.setDatePartAccess,
        )
    }

    /** Multi-step variant of [pushStep]. */
    fun pushSteps(vararg updates: AttributeRecipeStep): AttributeRecipe {
        var result = this
        for (u in updates) result = result.pushStep(u)
        return result
    }

    /**
     * Resolve the complete set of [GroupByItemProperty] for this recipe.
     *
     * Mirrors Python's `resolve_complete_properties`. Some properties (LOCAL /
     * JOINED / MULTI_HOP / DERIVED_TIME_GRANULARITY) depend on the number of
     * joined models or on grain comparisons and can only be determined once
     * the full traversal is complete.
     */
    fun resolveCompleteProperties(): OrderedSet<GroupByItemProperty> {
        val elementType = elementType
            ?: throw IllegalStateException("Recipe is missing the element type: $this")

        val properties = MutableOrderedSet<GroupByItemProperty>()
        properties.addAll(elementProperties)

        val modelIds = joinedModelIds
        when (modelIds.size) {
            0 -> {
                if (GroupByItemProperty.METRIC_TIME !in properties) {
                    throw IllegalStateException("Recipe is missing context on accessed semantic models: $this")
                }
            }
            1 -> {
                if (elementType != LinkableElementType.METRIC && GroupByItemProperty.METRIC_TIME !in properties) {
                    properties.add(GroupByItemProperty.LOCAL)
                }
            }
            2 -> properties.add(GroupByItemProperty.JOINED)
            else -> if (modelIds.size >= 3) {
                properties.add(GroupByItemProperty.JOINED)
                properties.add(GroupByItemProperty.MULTI_HOP)
            } else {
                throw MetricFlowInternalError("Reached unhandled case for model_id_count=${modelIds.size} recipe=$this")
            }
        }

        val src = sourceTimeGrain
        val grain = recipeTimeGrain
        if (src != null) {
            if (grain == null && recipeDatePart == null) {
                throw IllegalStateException(
                    "Recipe has a source time-grain, but no recipe time-grain or recipe date-part: $this",
                )
            }
            if (grain != null && src != grain.baseGranularity) {
                properties.add(GroupByItemProperty.DERIVED_TIME_GRANULARITY)
            }
        }

        return properties
    }

    /**
     * Resolve the element name from the indexed dunder name.
     *
     * Returns `null` if the recipe is incomplete (no element type or no
     * dunder-name elements yet).
     */
    fun resolveElementName(): String? {
        val elementType = elementType ?: return null
        val parts = indexedDunderName
        if (parts.isEmpty()) return null

        return when (elementType) {
            LinkableElementType.TIME_DIMENSION ->
                if (parts.size == 1) parts.last() else parts[parts.size - 2]
            LinkableElementType.ENTITY,
            LinkableElementType.DIMENSION,
            LinkableElementType.METRIC,
            -> parts.last()
        }
    }

    companion object {
        /** Start a recipe with an initial step. */
        fun create(initialStep: AttributeRecipeStep): AttributeRecipe {
            val dunderNameElements: List<String> =
                if (initialStep.addDunderNameElement != null) listOf(initialStep.addDunderNameElement) else emptyList()
            val entityLinkNames: List<String> =
                if (initialStep.addEntityLink != null) listOf(initialStep.addEntityLink) else emptyList()
            val models: List<SemanticModelId> =
                if (initialStep.addModelJoin != null) listOf(initialStep.addModelJoin) else emptyList()

            return AttributeRecipe(
                indexedDunderName = dunderNameElements,
                joinedModelIds = models,
                elementProperties = FrozenOrderedSet(initialStep.addProperties ?: emptyList()),
                elementType = initialStep.setElementType,
                entityLinkNames = entityLinkNames,
                sourceTimeGrain = initialStep.setSourceTimeGrain,
                recipeTimeGrain = initialStep.setTimeGrainAccess,
                recipeDatePart = initialStep.setDatePartAccess,
            )
        }

        /** An empty recipe. */
        val EMPTY: AttributeRecipe = AttributeRecipe(
            indexedDunderName = emptyList(),
            joinedModelIds = emptyList(),
            elementProperties = FrozenOrderedSet(),
            entityLinkNames = emptyList(),
            elementType = null,
            sourceTimeGrain = null,
            recipeTimeGrain = null,
            recipeDatePart = null,
        )
    }
}
