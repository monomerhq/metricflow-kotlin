package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Java8TimePeriodAdjusterTest {

    private val adjuster = Java8TimePeriodAdjuster()

    @Test
    fun `adjust to start of day strips time of day`() {
        val input = LocalDateTime.of(2026, 5, 15, 14, 30, 45)
        assertEquals(
            LocalDateTime.of(2026, 5, 15, 0, 0),
            adjuster.adjustToStartOfPeriod(TimeGranularity.DAY, input),
        )
    }

    @Test
    fun `adjust to start of month`() {
        val input = LocalDateTime.of(2026, 5, 15, 14, 30, 0)
        assertEquals(
            LocalDateTime.of(2026, 5, 1, 0, 0),
            adjuster.adjustToStartOfPeriod(TimeGranularity.MONTH, input),
        )
    }

    @Test
    fun `adjust to end of month`() {
        val input = LocalDateTime.of(2026, 2, 15, 14, 30, 0)
        assertEquals(
            LocalDateTime.of(2026, 2, 28, 0, 0),
            adjuster.adjustToEndOfPeriod(TimeGranularity.MONTH, input),
        )
    }

    @Test
    fun `adjust to start of quarter Q1`() {
        val input = LocalDateTime.of(2026, 2, 15, 0, 0)
        assertEquals(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            adjuster.adjustToStartOfPeriod(TimeGranularity.QUARTER, input),
        )
    }

    @Test
    fun `adjust to start of quarter Q4`() {
        val input = LocalDateTime.of(2026, 11, 15, 0, 0)
        assertEquals(
            LocalDateTime.of(2026, 10, 1, 0, 0),
            adjuster.adjustToStartOfPeriod(TimeGranularity.QUARTER, input),
        )
    }

    @Test
    fun `adjust to end of quarter`() {
        val input = LocalDateTime.of(2026, 5, 15, 0, 0)
        assertEquals(
            LocalDateTime.of(2026, 6, 30, 0, 0),
            adjuster.adjustToEndOfPeriod(TimeGranularity.QUARTER, input),
        )
    }

    @Test
    fun `adjust to start of year`() {
        val input = LocalDateTime.of(2026, 5, 15, 14, 30, 0)
        assertEquals(
            LocalDateTime.of(2026, 1, 1, 0, 0),
            adjuster.adjustToStartOfPeriod(TimeGranularity.YEAR, input),
        )
    }

    @Test
    fun `subsecond granularity is rejected`() {
        val input = LocalDateTime.of(2026, 5, 15, 14, 30, 0)
        assertFailsWith<IllegalArgumentException> {
            adjuster.adjustToStartOfPeriod(TimeGranularity.MILLISECOND, input)
        }
    }

    @Test
    fun `expand cumulative shifts start backward by window`() {
        val constraint = TimeRangeConstraint(
            startTime = LocalDateTime.of(2026, 5, 15, 0, 0),
            endTime = LocalDateTime.of(2026, 6, 15, 0, 0),
        )
        val out = adjuster.expandTimeConstraintForCumulativeMetric(
            timeConstraint = constraint,
            granularity = TimeGranularity.MONTH,
            count = 2,
        )
        assertEquals(LocalDateTime.of(2026, 3, 15, 0, 0), out.startTime)
        assertEquals(LocalDateTime.of(2026, 6, 15, 0, 0), out.endTime)
    }

    @Test
    fun `expand cumulative without grain rolls start to ALL_TIME_BEGIN`() {
        val constraint = TimeRangeConstraint(
            startTime = LocalDateTime.of(2026, 5, 15, 0, 0),
            endTime = LocalDateTime.of(2026, 6, 15, 0, 0),
        )
        val out = adjuster.expandTimeConstraintForCumulativeMetric(
            timeConstraint = constraint,
            granularity = null,
            count = 0,
        )
        assertEquals(TimeRangeConstraint.ALL_TIME_BEGIN, out.startTime)
    }

    @Test
    fun `expand to fill granularity rounds endpoints`() {
        val constraint = TimeRangeConstraint(
            startTime = LocalDateTime.of(2026, 1, 15, 0, 0),
            endTime = LocalDateTime.of(2026, 2, 15, 0, 0),
        )
        val out = adjuster.expandTimeConstraintToFillGranularity(constraint, TimeGranularity.MONTH)
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), out.startTime)
        assertEquals(LocalDateTime.of(2026, 2, 28, 0, 0), out.endTime)
    }
}
