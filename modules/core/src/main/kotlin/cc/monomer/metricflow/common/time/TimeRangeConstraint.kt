package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.common.errors.UnableToSatisfyQueryError
import java.time.LocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private object LocalDateTimeAsIsoStringSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDateTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDateTime =
        LocalDateTime.parse(decoder.decodeString())
}

/**
 * Describes how the time dimension for metrics should be constrained.
 *
 * Port of `metricflow_semantics.filters.time_constraint.TimeRangeConstraint`.
 *
 * Lives under `:common:time` rather than `:common:filter` to keep the
 * package layout aligned with metricflow's domain vocabulary: queries refer
 * to "time constraints" and the type is always seen alongside other time
 * concepts.
 *
 * Construction enforces:
 * - `endTime <= ALL_TIME_END` → throws [UnableToSatisfyQueryError]
 *   (Python also throws). The Python `start > end` and
 *   `start < ALL_TIME_BEGIN` checks emit a warning but do not throw; we
 *   accept silently as well to keep the engine output identical.
 */
@Serializable
data class TimeRangeConstraint(
    @Serializable(with = LocalDateTimeAsIsoStringSerializer::class)
    val startTime: LocalDateTime,
    @Serializable(with = LocalDateTimeAsIsoStringSerializer::class)
    val endTime: LocalDateTime,
) {

    init {
        if (endTime.isAfter(ALL_TIME_END)) {
            throw UnableToSatisfyQueryError(
                errorName = "end_time exceeds the supported range",
                context = mapOf("end_time" to endTime.toString(), "limit" to ALL_TIME_END.toString()),
            )
        }
    }

    /** Returns `true` if every instant in this range is also in [other]. */
    fun isSubsetOf(other: TimeRangeConstraint): Boolean =
        !startTime.isBefore(other.startTime) && !endTime.isAfter(other.endTime)

    /** Returns the intersection of this range with [other], or [emptyTime] if disjoint. */
    fun intersection(other: TimeRangeConstraint): TimeRangeConstraint = when {
        endTime.isBefore(other.startTime) -> emptyTime()
        other.endTime.isBefore(startTime) -> emptyTime()
        else -> TimeRangeConstraint(
            startTime = maxOf(startTime, other.startTime),
            endTime = minOf(endTime, other.endTime),
        )
    }

    override fun toString(): String = "[${startTime}, ${endTime}]"

    companion object {
        /** Earliest representable instant. Matches `TimeRangeConstraint.ALL_TIME_BEGIN()`. */
        val ALL_TIME_BEGIN: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)

        /** Latest representable instant. Matches `TimeRangeConstraint.ALL_TIME_END()`. */
        val ALL_TIME_END: LocalDateTime = LocalDateTime.of(2040, 12, 31, 0, 0, 0)

        /** Range covering every supported instant. */
        fun allTime(): TimeRangeConstraint = TimeRangeConstraint(ALL_TIME_BEGIN, ALL_TIME_END)

        /** Empty range — used as the zero element for [intersection]. */
        fun emptyTime(): TimeRangeConstraint = TimeRangeConstraint(ALL_TIME_BEGIN, ALL_TIME_BEGIN)
    }
}
