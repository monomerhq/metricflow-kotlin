package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.ElementReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference

/**
 * Static helper methods for retrieving items from a [SemanticModel].
 *
 * Port of `metricflow_semantics/model/semantics/semantic_model_helper.py::SemanticModelHelper`.
 *
 * Python's class consists only of `@staticmethod`s; in Kotlin we use a top-level `object`. The
 * helpers cover three lookups that recur across the model layer:
 * - resolving the primary entity (declared explicitly or via an entity with `type == PRIMARY`),
 * - listing entity links usable as join-path prefixes for local dimensions,
 * - fetching individual dimensions or time-dimension grains from a model.
 */
object SemanticModelHelper {

    /**
     * Return the primary entity for dimensions in the model.
     *
     * Semantic models with measures or dimensions should have a primary entity as enforced by
     * semantic-manifest validation. Resolution order matches Python:
     * 1. If `semanticModel.primaryEntity` is set, use it.
     * 2. Otherwise, find the unique entity with `type == PRIMARY`.
     * 3. Otherwise throw — validation should have prevented this.
     */
    fun resolvedPrimaryEntity(semanticModel: SemanticModel): EntityReference {
        val explicit = semanticModel.primaryEntityReference
        val typedPrimaries = semanticModel.entities.filter { it.type == EntityType.PRIMARY }

        // Sanity check matching the Python `assert`.
        check(typedPrimaries.size <= 1) { "Found > 1 primary entity in $semanticModel" }

        if (explicit != null) {
            check(typedPrimaries.isEmpty()) {
                "The primary_entity field was set to $explicit, but there are non-zero entities " +
                    "with type ${EntityType.PRIMARY} in $semanticModel"
            }
            return explicit
        }

        if (typedPrimaries.isNotEmpty()) {
            return typedPrimaries[0].reference
        }

        throw IllegalArgumentException("No primary entity found in semantic_model `${semanticModel.name}`")
    }

    /**
     * Return the entity prefixes usable to access dimensions defined in the given semantic model.
     *
     * Ordered alphabetically by element name to match the Python `sorted(...)` output.
     */
    fun entityLinksForLocalElements(semanticModel: SemanticModel): List<EntityReference> {
        val possible: MutableSet<EntityReference> = LinkedHashSet()
        semanticModel.primaryEntityReference?.let { possible.add(it) }
        for (entity in semanticModel.entities) {
            if (entity.isLinkableEntityType) {
                possible.add(entity.reference)
            }
        }
        return possible.sortedBy { it.elementName }
    }

    /**
     * Get a dimension from [semanticModel] by reference.
     *
     * Accepts any [ElementReference] (typically a [cc.monomer.metricflow.domain.manifest.model.references.DimensionReference]
     * or [TimeDimensionReference]) since references compare structurally by element name. Throws
     * if the dimension is not present.
     */
    fun getDimensionFromSemanticModel(
        semanticModel: SemanticModel,
        dimensionReference: ElementReference,
    ): Dimension {
        for (dim in semanticModel.dimensions) {
            if (dim.reference.elementName == dimensionReference.elementName) return dim
        }
        val available = semanticModel.dimensions.map { it.name }
        throw IllegalArgumentException(
            "Unable to find matching dimension for the given reference. " +
                "dimension_reference=${dimensionReference.elementName} " +
                "semantic_model_name=${semanticModel.name} dimensions=$available",
        )
    }

    /**
     * Return a mapping of the defined time granularity of the time dimensions in the semantic model.
     *
     * Throws if a time dimension is missing its `type_params` (validation should have prevented this).
     */
    fun getTimeDimensionGrains(
        semanticModel: SemanticModel,
    ): Map<TimeDimensionReference, TimeGranularity> {
        val out: MutableMap<TimeDimensionReference, TimeGranularity> = LinkedHashMap()
        for (dim in semanticModel.dimensions) {
            when (dim.type) {
                DimensionType.TIME -> {
                    val params = dim.typeParams
                        ?: throw IllegalStateException(
                            "A dimension is specified as a time dimension but does not specify a grain. " +
                                "This should have been caught in semantic-manifest validation. " +
                                "dimension=${dim.name} semantic_model=${semanticModel.name}",
                        )
                    out[TimeDimensionReference(dim.name)] = params.timeGranularity
                }
                DimensionType.CATEGORICAL -> Unit
            }
        }
        return out
    }
}
