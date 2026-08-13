package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Currently supported metric types.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/metric_type.py::MetricType`.
 */
@Serializable
enum class MetricType(val value: String) {
    @SerialName("simple") SIMPLE("simple"),
    @SerialName("ratio") RATIO("ratio"),
    @SerialName("cumulative") CUMULATIVE("cumulative"),
    @SerialName("derived") DERIVED("derived"),
    @SerialName("conversion") CONVERSION("conversion"),
}
