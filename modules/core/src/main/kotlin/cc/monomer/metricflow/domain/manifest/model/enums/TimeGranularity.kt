package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * For time dimensions, the smallest possible difference between two time values.
 *
 * Names are used in parameters to DATE_TRUNC, so don't change them.
 * Values are used to convert user supplied strings to enums.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/time_granularity.py::TimeGranularity`.
 */
@Serializable
enum class TimeGranularity(val value: String) {
    @SerialName("nanosecond") NANOSECOND("nanosecond"),
    @SerialName("microsecond") MICROSECOND("microsecond"),
    @SerialName("millisecond") MILLISECOND("millisecond"),
    @SerialName("second") SECOND("second"),
    @SerialName("minute") MINUTE("minute"),
    @SerialName("hour") HOUR("hour"),
    @SerialName("day") DAY("day"),
    @SerialName("week") WEEK("week"),
    @SerialName("month") MONTH("month"),
    @SerialName("quarter") QUARTER("quarter"),
    @SerialName("year") YEAR("year");

    /** Convert to an int so that the size of the granularity can be easily compared. */
    fun toInt(): Int = when (this) {
        NANOSECOND -> 4
        MICROSECOND -> 5
        MILLISECOND -> 6
        SECOND -> 7
        MINUTE -> 8
        HOUR -> 9
        DAY -> 10
        WEEK -> 11
        MONTH -> 12
        QUARTER -> 13
        YEAR -> 14
    }

    fun isSmallerThan(other: TimeGranularity): Boolean = toInt() < other.toInt()

    fun isSmallerThanOrEqual(other: TimeGranularity): Boolean = toInt() <= other.toInt()

    companion object {
        /** Look up a `TimeGranularity` by its lowercase value. Mirrors `string_to_time_granularity`. */
        fun fromString(value: String): TimeGranularity =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unknown TimeGranularity value: $value")
    }
}
