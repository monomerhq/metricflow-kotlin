package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType

/**
 * Helpers shared by multiple validation rules.
 *
 * Port of
 * `metricflow_semantic_interfaces/validations/validator_helpers.py::SemanticModelValidationHelpers`.
 */
internal object SemanticModelValidationHelpers {

    /** True iff [semanticModel] has a TIME dimension with the given name. */
    fun timeDimensionInModel(timeDimensionName: String, semanticModel: SemanticModel): Boolean =
        semanticModel.dimensions.any { it.type == DimensionType.TIME && it.name == timeDimensionName }
}

/**
 * Holds invariants checked across semantic models in [cc.monomer.metricflow.domain.manifest.validation.rules.DimensionConsistencyRule].
 *
 * Port of `validator_helpers.py::DimensionInvariants`.
 */
internal data class DimensionInvariants(
    val type: DimensionType,
    val isPartition: Boolean,
)
