package cc.monomer.metricflow.domain.semantic_graph.lookup

import cc.monomer.metricflow.common.errors.InvalidManifestException
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId

/**
 * Indexes a single semantic model.
 *
 * Port of `metricflow_semantics/semantic_graph/lookups/model_object_lookup.py::ModelObjectLookup`.
 *
 * The "object lookup" name is preserved from Python and means "lookup over
 * already-validated manifest objects" — distinct from the W7a
 * [cc.monomer.metricflow.domain.lookup.SemanticModelLookup] which
 * indexes across models.
 */
open class ModelObjectLookup(val semanticModel: SemanticModel) {

    /** Bridge to [SemanticModelId]. */
    val modelId: SemanticModelId = SemanticModelId.getInstance(semanticModel.name)

    /** Mapping from time-dimension name to its declared grain. */
    val timeDimensionNameToGrain: Map<String, TimeGranularity> = buildMap {
        for (dimension in semanticModel.dimensions) {
            when (dimension.type) {
                DimensionType.TIME -> {
                    val typeParams = dimension.typeParams ?: throw InvalidManifestException(
                        "`type_params` should not be `null` for a time dimension. " +
                            "dimension=${dimension.name} semantic_model=${semanticModel.name}",
                    )
                    put(dimension.name, typeParams.timeGranularity)
                }
                DimensionType.CATEGORICAL -> Unit
            }
        }
    }

    /** Lookup of entities defined on this model. */
    val entityLookup: EntityLookup = EntityLookup(semanticModel.entities)
}
