package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Options for how to aggregate across a time period.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/period_agg.py::PeriodAggregation`.
 */
@Serializable
enum class PeriodAggregation(val value: String) {
    @SerialName("first") FIRST("first"),
    @SerialName("last") LAST("last"),
    @SerialName("average") AVERAGE("average"),
}
