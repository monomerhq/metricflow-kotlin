package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.errors.InvalidSemanticModelError
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Tracks semantic information for the semantic models held by a [SemanticManifest].
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_lookup.py::SemanticModelLookup`.
 *
 * Eagerly builds three indexes over the manifest's models (ordered by `semantic_model.name`
 * to match Python's `sorted(...)`):
 *
 * - `DimensionReference -> List<SemanticModel>` — every model that defines a dimension.
 * - `EntityReference -> List<SemanticModel>` — every model that defines an entity.
 * - `SemanticModelReference -> SemanticModel` — name-to-model lookup.
 *
 * It also constructs a [DimensionLookup] for invariant checks across multiple definitions of
 * the same dimension.
 *
 * ### Scope note
 *
 * Python's `SemanticModelLookup` exposes a `get_element_spec_for_name(name)` method that
 * returns a `LinkableInstanceSpec` (sealed union over `DimensionSpec` / `EntitySpec` /
 * `TimeDimensionSpec`). Those spec types live in `:domain:spec` (W7c) and are not yet ported.
 * The spec-returning method is therefore deferred to W7c, but every spec-free method in the
 * Python class is ported here.
 */
class SemanticModelLookup(
    semanticManifest: SemanticManifest,
    /**
     * Mapping of custom-grain name to the lifted [ExpandedTimeGranularity] representation,
     * produced by `TimeSpineSource.buildCustomGranularities`. Held by reference so downstream
     * lookups (e.g. dimension dunder-name parsing in W7c) can reach the same values.
     */
    val customGranularities: Map<String, ExpandedTimeGranularity>,
) {

    private val dimensionIndex: MutableMap<DimensionReference, MutableList<SemanticModel>> = LinkedHashMap()

    /**
     * Map from entity reference to the list of semantic models that define that entity. Public to
     * mirror the Python attribute access pattern.
     */
    val entityIndex: Map<EntityReference, List<SemanticModel>>
        get() = entityIndexMutable

    private val entityIndexMutable: MutableMap<EntityReference, MutableList<SemanticModel>> = LinkedHashMap()

    private val semanticModelReferenceToSemanticModel: MutableMap<SemanticModelReference, SemanticModel> =
        LinkedHashMap()

    /** Names of every custom granularity registered with this lookup. */
    val customGranularityNames: List<String> = customGranularities.keys.toList()

    /** Dimension-invariant lookup built from the same manifest. */
    val dimensionLookup: DimensionLookup

    init {
        val sortedSemanticModels = semanticManifest.semanticModels.sortedBy { it.name }
        for (semanticModel in sortedSemanticModels) {
            addSemanticModel(semanticModel)
        }
        dimensionLookup = DimensionLookup(sortedSemanticModels)
    }

    private fun addSemanticModel(semanticModel: SemanticModel) {
        if (semanticModel.reference in semanticModelReferenceToSemanticModel) {
            throw InvalidSemanticModelError(
                "Got errors adding the given semantic model: " +
                    "semantic_model=${semanticModel.name} " +
                    "errors=[Semantic model ${semanticModel.reference} already added.]",
            )
        }

        for (dim in semanticModel.dimensions) {
            dimensionIndex.getOrPut(dim.reference) { mutableListOf() }.add(semanticModel)
        }

        for (entity in semanticModel.entities) {
            entityIndexMutable.getOrPut(entity.reference) { mutableListOf() }.add(semanticModel)
        }

        semanticModelReferenceToSemanticModel[semanticModel.reference] = semanticModel
    }

    /** Retrieve all dimension references from the collection of semantic models. */
    fun getDimensionReferences(): List<DimensionReference> = dimensionIndex.keys.toList()

    /** Retrieve all entity references from the collection of semantic models. */
    fun getEntityReferences(): List<EntityReference> = entityIndexMutable.keys.toList()

    /**
     * Retrieve the entity matching the element-to-semantic-model mapping, if any. Returns
     * `null` if either the semantic model or the entity is unknown.
     */
    fun getEntityInSemanticModel(ref: SemanticModelElementReference): Entity? {
        val semanticModel = getByReference(ref.semanticModelReference) ?: return null
        for (entity in semanticModel.entities) {
            if (entity.reference.elementName == ref.elementName) return entity
        }
        return null
    }

    /** Retrieve the semantic model matching the input reference, or `null` if absent. */
    fun getByReference(semanticModelReference: SemanticModelReference): SemanticModel? =
        semanticModelReferenceToSemanticModel[semanticModelReference]

    /** Return all semantic models associated with [entityReference] (as a set, matching Python). */
    fun getSemanticModelsForEntity(entityReference: EntityReference): Set<SemanticModel> =
        entityIndexMutable[entityReference]?.toSet() ?: emptySet()

    /** Return all semantic models associated with [dimensionReference] (as a set). */
    fun getSemanticModelsForDimension(dimensionReference: DimensionReference): Set<SemanticModel> =
        dimensionIndex[dimensionReference]?.toSet() ?: emptySet()

    /** Returns an ordered, name-sorted view of the model-reference-to-model map. */
    val modelReferenceToModel: Map<SemanticModelReference, SemanticModel>
        get() = semanticModelReferenceToSemanticModel
            .toSortedMap(compareBy { it.semanticModelName })
}
