package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aggregation methods for measures.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/aggregation_type.py::AggregationType`.
 */
@Serializable
enum class AggregationType(val value: String) {
    @SerialName("sum") SUM("sum"),
    @SerialName("min") MIN("min"),
    @SerialName("max") MAX("max"),
    @SerialName("count_distinct") COUNT_DISTINCT("count_distinct"),
    @SerialName("sum_boolean") SUM_BOOLEAN("sum_boolean"),
    @SerialName("average") AVERAGE("average"),
    @SerialName("percentile") PERCENTILE("percentile"),
    @SerialName("median") MEDIAN("median"),
    @SerialName("count") COUNT("count"),
}
