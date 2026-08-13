package cc.monomer.metricflow.domain.manifest.model.element

import cc.monomer.metricflow.domain.manifest.model.Metadata
import cc.monomer.metricflow.domain.manifest.model.SemanticLayerElementConfig
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import kotlinx.serialization.Serializable

/**
 * A grouping column declaration inside a semantic model.
 *
 * Port of `metricflow_semantic_interfaces/implementations/elements/dimension.py::PydanticDimension`.
 */
@Serializable
data class Dimension(
    val name: String,
    val description: String? = null,
    val type: DimensionType,
    val isPartition: Boolean = false,
    val typeParams: DimensionTypeParams? = null,
    val expr: String? = null,
    val metadata: Metadata? = null,
    val label: String? = null,
    val config: SemanticLayerElementConfig? = null,
) {
    val reference: DimensionReference get() = DimensionReference(name)

    /** Non-null iff this is a TIME dimension. */
    val timeDimensionReference: TimeDimensionReference?
        get() = if (type == DimensionType.TIME) TimeDimensionReference(name) else null

    /** Returns the validity params, if set. */
    val validityParams: DimensionValidityParams?
        get() = typeParams?.validityParams
}

/**
 * Time-specific extra parameters for a dimension.
 *
 * Port of `PydanticDimensionTypeParams`.
 */
@Serializable
data class DimensionTypeParams(
    val timeGranularity: TimeGranularity,
    val validityParams: DimensionValidityParams? = null,
)

/**
 * SCD Type II window markers — `is_start`/`is_end` flag which time dimension delimits the
 * validity window of a slowly-changing dimension semantic model.
 *
 * Port of `PydanticDimensionValidityParams`.
 */
@Serializable
data class DimensionValidityParams(
    val isStart: Boolean = false,
    val isEnd: Boolean = false,
)
