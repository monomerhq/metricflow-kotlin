package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Determines types of values expected of dimensions.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/dimension_type.py::DimensionType`.
 */
@Serializable
enum class DimensionType(val value: String) {
    @SerialName("categorical") CATEGORICAL("categorical"),
    @SerialName("time") TIME("time");

    /** Checks if this type of dimension is a time type. */
    fun isTimeType(): Boolean = this == TIME
}
