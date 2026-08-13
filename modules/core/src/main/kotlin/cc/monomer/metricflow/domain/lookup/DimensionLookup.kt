package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference

/**
 * Properties that must agree across every definition of a dimension with a given name.
 *
 * Port of `metricflow_semantics/model/semantics/dimension_lookup.py::DimensionInvariant`.
 *
 * For a given manifest, all defined dimensions with the same name must share the same
 * [dimensionType] and [isPartition] values. `DimensionLookup`'s constructor enforces this.
 */
data class DimensionInvariant(
    val dimensionType: DimensionType,
    val isPartition: Boolean,
)

/**
 * Looks up properties related to dimensions in a [SemanticManifest][cc.monomer.metricflow.domain.manifest.model.SemanticManifest].
 *
 * Port of `metricflow_semantics/model/semantics/dimension_lookup.py::DimensionLookup`.
 *
 * Two indexes are built eagerly:
 *
 * - `dimension_reference -> DimensionInvariant`. If two definitions of the same dimension
 *   declare conflicting values, the constructor throws [IllegalStateException] — validation
 *   should have caught this upstream.
 * - `dunder_name -> Dimension`. The dunder name is `<primary-entity>__<dimension-name>`
 *   computed via [SemanticModelHelper.resolvedPrimaryEntity]. Used to resolve a dimension
 *   from its qualified name when the primary entity isn't known at the call site.
 */
class DimensionLookup(semanticModels: List<SemanticModel>) {

    private val dimensionReferenceToInvariant: MutableMap<DimensionReference, DimensionInvariant> = LinkedHashMap()

    /**
     * Map from `"<primary-entity>__<dimension-name>"` to the originating [Dimension]. Public to
     * match the Python attribute access pattern (`dimension_lookup.dimensions_by_dunder_name`).
     */
    val dimensionsByDunderName: Map<String, Dimension>

    init {
        val byDunder: MutableMap<String, Dimension> = LinkedHashMap()
        for (semanticModel in semanticModels) {
            for (dimension in semanticModel.dimensions) {
                val invariant = DimensionInvariant(
                    dimensionType = dimension.type,
                    isPartition = dimension.isPartition,
                )
                val dimensionReference = dimension.reference
                val existing = dimensionReferenceToInvariant[dimensionReference]
                if (existing != null && existing != invariant) {
                    throw IllegalStateException(
                        "Dimensions with the same name have been defined with conflicting values " +
                            "that should have been the same in a given semantic manifest. " +
                            "This should have been caught during validation. " +
                            "dimension_reference=$dimensionReference existing_invariant=$existing " +
                            "conflicting_invariant=$invariant semantic_model_reference=${semanticModel.reference}",
                    )
                }
                dimensionReferenceToInvariant[dimensionReference] = invariant

                val primaryEntity = SemanticModelHelper.resolvedPrimaryEntity(semanticModel)
                val dunder = "${primaryEntity.elementName}__${dimension.name}"
                byDunder[dunder] = dimension
            }
        }
        dimensionsByDunderName = byDunder.toMap()
    }

    /**
     * Get invariants for the given dimension in the semantic manifest.
     *
     * A `TimeDimensionReference` is accepted and resolved against the dimension-keyed index.
     * Throws [IllegalArgumentException] if no invariant exists for the given name.
     */
    fun getInvariant(dimensionReference: DimensionReference): DimensionInvariant =
        dimensionReferenceToInvariant[dimensionReference]
            ?: throw IllegalArgumentException(
                "Unknown dimension reference: ${dimensionReference.elementName}. " +
                    "Known: ${dimensionReferenceToInvariant.keys.map { it.elementName }}",
            )
}
