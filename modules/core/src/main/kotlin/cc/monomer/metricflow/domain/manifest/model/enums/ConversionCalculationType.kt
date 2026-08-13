package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Types of calculations for a conversion metric.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/conversion_calculation_type.py::ConversionCalculationType`.
 */
@Serializable
enum class ConversionCalculationType(val value: String) {
    @SerialName("conversions") CONVERSIONS("conversions"),
    @SerialName("conversion_rate") CONVERSION_RATE("conversion_rate"),
}
