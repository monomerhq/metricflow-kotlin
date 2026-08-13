package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.common.dag.DisplayedProperty
import cc.monomer.metricflow.common.graph.HasDisplayedProperty
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.lookup.GroupByItemProperty
import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId

/**
 * A step that should be appended to an [AttributeRecipe] as it is constructed
 * by walking a path in the semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/attribute_resolution/attribute_recipe_step.py::AttributeRecipeStep`.
 *
 * Every node and edge in the semantic graph contributes a recipe step
 * describing how the path's state evolves at that point (add to the dunder
 * name, add an entity link, change the element type, etc.).
 */
data class AttributeRecipeStep(
    /**
     * Append this element to the resolved dunder name (e.g. an edge from the
     * `user` entity node to the `country_latest` attribute node appends
     * `"country_latest"`).
     */
    val addDunderNameElement: String?,
    /** Properties that describe this step (e.g. `METRIC_TIME` at the metric-time entity node). */
    val addProperties: List<GroupByItemProperty>?,
    /** A model joined as part of this step. */
    val addModelJoin: SemanticModelId?,
    /** An entity link added by this step. */
    val addEntityLink: String?,
    /** Sets the element type (e.g. visiting a time-dimension entity sets `TIME_DIMENSION`). */
    val setElementType: LinkableElementType?,
    /** Source time-grain set by visiting a time dimension. */
    val setSourceTimeGrain: TimeGranularity?,
    /** Time grain access that this step grants (used together with [setSourceTimeGrain]). */
    val setTimeGrainAccess: ExpandedTimeGranularity?,
    /** Date part access that this step grants. */
    val setDatePartAccess: DatePart?,
    /** Some edges prevent date-part attribute access (e.g. cumulative metrics). */
    val setDenyDatePart: Boolean?,
) : HasDisplayedProperty {

    override val displayedProperties: List<DisplayedProperty>
        get() = buildList {
            if (addDunderNameElement != null) {
                add(DisplayedProperty("add_name", addDunderNameElement))
            }
            for (prop in addProperties ?: emptyList()) {
                add(DisplayedProperty("add_prop", prop.name))
            }
            if (addModelJoin != null) {
                add(DisplayedProperty("add_model_join", addModelJoin.modelName))
            }
            if (setSourceTimeGrain != null) {
                add(DisplayedProperty("set_min_grain", setSourceTimeGrain.name))
            }
            if (setElementType != null) {
                add(DisplayedProperty("add_type", setElementType.name))
            }
            if (addEntityLink != null) {
                add(DisplayedProperty("add_entity_link", addEntityLink))
            }
            if (setTimeGrainAccess != null) {
                add(DisplayedProperty("set_time_grain", setTimeGrainAccess.name))
            }
            if (setDatePartAccess != null) {
                add(DisplayedProperty("set_date_part", setDatePartAccess.name))
            }
            if (setDenyDatePart != null) {
                add(DisplayedProperty("set_deny_date_part", setDenyDatePart.toString()))
            }
        }

    companion object {
        /** A no-op step. Equivalent to Python's `AttributeRecipeStep()` zero-arg constructor. */
        val EMPTY: AttributeRecipeStep = AttributeRecipeStep(
            addDunderNameElement = null,
            addProperties = null,
            addModelJoin = null,
            addEntityLink = null,
            setElementType = null,
            setSourceTimeGrain = null,
            setTimeGrainAccess = null,
            setDatePartAccess = null,
            setDenyDatePart = null,
        )
    }
}

/**
 * Interface for a class that provides a step that can be appended to a recipe.
 *
 * Port of `AttributeRecipeStepProvider` — implemented by every semantic-graph
 * node and edge.
 */
interface AttributeRecipeStepProvider : HasDisplayedProperty {
    /** Step appended when this provider is traversed. Defaults to no-op. */
    val recipeStepToAppend: AttributeRecipeStep get() = AttributeRecipeStep.EMPTY
}
