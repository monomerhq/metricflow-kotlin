package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import java.time.LocalDateTime
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

/**
 * Interface to simplify switching time-period adjustment logic between backends.
 *
 * Port of `metricflow_semantics.time.time_period.TimePeriodAdjuster`. Python
 * has two implementations (`PandasTimePeriodAdjuster`, `DateutilTimePeriodAdjuster`);
 * the metricflow code path uses the dateutil-backed one which we port below
 * as [Java8TimePeriodAdjuster].
 */
interface TimePeriodAdjuster {
    fun adjustToStartOfPeriod(timeGranularity: TimeGranularity, dateToAdjust: LocalDateTime): LocalDateTime
    fun adjustToEndOfPeriod(timeGranularity: TimeGranularity, dateToAdjust: LocalDateTime): LocalDateTime
    fun expandTimeConstraintToFillGranularity(
        timeConstraint: TimeRangeConstraint,
        granularity: TimeGranularity,
    ): TimeRangeConstraint
    fun expandTimeConstraintForCumulativeMetric(
        timeConstraint: TimeRangeConstraint,
        granularity: TimeGranularity?,
        count: Int,
    ): TimeRangeConstraint
}

/**
 * Implementation of [TimePeriodAdjuster] using `java.time` (the JVM
 * equivalent of Python's `dateutil`).
 *
 * Port of `metricflow_semantics.time.dateutil_adjuster.DateutilTimePeriodAdjuster`.
 *
 * Sub-second granularities (`NANOSECOND`/`MICROSECOND`/`MILLISECOND`) are
 * rejected with [IllegalArgumentException] — matching Python's
 * `ValueError("Time constraints only support SECOND or larger time granularities.")`.
 */
class Java8TimePeriodAdjuster : TimePeriodAdjuster {

    override fun adjustToStartOfPeriod(timeGranularity: TimeGranularity, dateToAdjust: LocalDateTime): LocalDateTime {
        rejectSubSecond(timeGranularity)
        // Always normalize microseconds (the Python flow strips microseconds first).
        val base = if (timeGranularity.toInt() >= TimeGranularity.DAY.toInt()) {
            dateToAdjust.withNano(0).withSecond(0).withMinute(0).withHour(0)
        } else {
            dateToAdjust.withNano(0)
        }
        return when (timeGranularity) {
            TimeGranularity.SECOND -> base
            TimeGranularity.MINUTE -> base.withSecond(0)
            TimeGranularity.HOUR -> base.withMinute(0).withSecond(0)
            TimeGranularity.DAY -> base
            TimeGranularity.WEEK -> base.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            TimeGranularity.MONTH -> base.withDayOfMonth(1)
            TimeGranularity.QUARTER -> base.with(IsoFields.QUARTER_OF_YEAR, base.get(IsoFields.QUARTER_OF_YEAR).toLong())
                .with(IsoFields.DAY_OF_QUARTER, 1L)
            TimeGranularity.YEAR -> base.withMonth(1).withDayOfMonth(1)
            TimeGranularity.NANOSECOND, TimeGranularity.MICROSECOND, TimeGranularity.MILLISECOND ->
                error("unreachable") // rejected above
        }
    }

    override fun adjustToEndOfPeriod(timeGranularity: TimeGranularity, dateToAdjust: LocalDateTime): LocalDateTime {
        rejectSubSecond(timeGranularity)
        val base = if (timeGranularity.toInt() >= TimeGranularity.DAY.toInt()) {
            dateToAdjust.withNano(0).withSecond(0).withMinute(0).withHour(0)
        } else {
            dateToAdjust.withNano(0)
        }
        return when (timeGranularity) {
            TimeGranularity.SECOND -> base
            TimeGranularity.MINUTE -> base.withSecond(59)
            TimeGranularity.HOUR -> base.withMinute(59).withSecond(59)
            TimeGranularity.DAY -> base
            TimeGranularity.WEEK -> base.with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY))
            TimeGranularity.MONTH -> base.with(TemporalAdjusters.lastDayOfMonth())
            TimeGranularity.QUARTER -> {
                val q = base.get(IsoFields.QUARTER_OF_YEAR)
                val month = q * 3
                base.withMonth(month).with(TemporalAdjusters.lastDayOfMonth())
            }
            TimeGranularity.YEAR -> base.withMonth(12).withDayOfMonth(31)
            TimeGranularity.NANOSECOND, TimeGranularity.MICROSECOND, TimeGranularity.MILLISECOND ->
                error("unreachable")
        }
    }

    override fun expandTimeConstraintToFillGranularity(
        timeConstraint: TimeRangeConstraint,
        granularity: TimeGranularity,
    ): TimeRangeConstraint {
        val rawStart = adjustToStartOfPeriod(granularity, timeConstraint.startTime)
        val rawEnd = adjustToEndOfPeriod(granularity, timeConstraint.endTime)
        val adjustedStart = if (rawStart.isBefore(TimeRangeConstraint.ALL_TIME_BEGIN)) TimeRangeConstraint.ALL_TIME_BEGIN else rawStart
        val adjustedEnd = if (rawEnd.isAfter(TimeRangeConstraint.ALL_TIME_END)) TimeRangeConstraint.ALL_TIME_END else rawEnd
        return TimeRangeConstraint(adjustedStart, adjustedEnd)
    }

    override fun expandTimeConstraintForCumulativeMetric(
        timeConstraint: TimeRangeConstraint,
        granularity: TimeGranularity?,
        count: Int,
    ): TimeRangeConstraint {
        if (granularity == null) {
            return TimeRangeConstraint(
                startTime = TimeRangeConstraint.ALL_TIME_BEGIN,
                endTime = timeConstraint.endTime,
            )
        }
        val shifted = subtractWindow(timeConstraint.startTime, granularity, count)
        return TimeRangeConstraint(startTime = shifted, endTime = timeConstraint.endTime)
    }

    private fun rejectSubSecond(timeGranularity: TimeGranularity) {
        if (timeGranularity == TimeGranularity.NANOSECOND ||
            timeGranularity == TimeGranularity.MICROSECOND ||
            timeGranularity == TimeGranularity.MILLISECOND
        ) {
            throw IllegalArgumentException("Time constraints only support SECOND or larger time granularities.")
        }
    }

    private fun subtractWindow(base: LocalDateTime, granularity: TimeGranularity, count: Int): LocalDateTime {
        rejectSubSecond(granularity)
        return when (granularity) {
            TimeGranularity.SECOND -> base.minusSeconds(count.toLong())
            TimeGranularity.MINUTE -> base.minusMinutes(count.toLong())
            TimeGranularity.HOUR -> base.minusHours(count.toLong())
            TimeGranularity.DAY -> base.minusDays(count.toLong())
            TimeGranularity.WEEK -> base.minusWeeks(count.toLong())
            TimeGranularity.MONTH -> base.minusMonths(count.toLong())
            TimeGranularity.QUARTER -> base.minusMonths(count.toLong() * 3)
            TimeGranularity.YEAR -> base.minusYears(count.toLong())
            else -> error("unreachable")
        }
    }
}
