package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Date parts able to be extracted from a time dimension.
 *
 * Does not support WEEK because week numbering is inconsistent across SQL engines.
 * See the Python docstring for the full rationale.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/date_part.py::DatePart`.
 */
@Serializable
enum class DatePart(val value: String) {
    @SerialName("year") YEAR("year"),
    @SerialName("quarter") QUARTER("quarter"),
    @SerialName("month") MONTH("month"),
    @SerialName("day") DAY("day"),
    @SerialName("dow") DOW("dow"),
    @SerialName("doy") DOY("doy");

    /** Convert to an int so that the size of the granularity can be easily compared. */
    fun toInt(): Int = when (this) {
        DAY, DOW, DOY -> TimeGranularity.DAY.toInt()
        MONTH -> TimeGranularity.MONTH.toInt()
        QUARTER -> TimeGranularity.QUARTER.toInt()
        YEAR -> TimeGranularity.YEAR.toInt()
    }

    /** Granularities that can be queried with this date part. */
    val compatibleGranularities: List<TimeGranularity>
        get() = TimeGranularity.entries.filter { it.toInt() <= toInt() }
}
