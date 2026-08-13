package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.common.errors.UnableToSatisfyQueryError
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeRangeConstraintTest {

    private fun dt(year: Int, month: Int, day: Int): LocalDateTime =
        LocalDateTime.of(year, month, day, 0, 0)

    @Test
    fun `ALL_TIME_BEGIN matches Python constant`() {
        assertEquals(LocalDateTime.of(2000, 1, 1, 0, 0), TimeRangeConstraint.ALL_TIME_BEGIN)
    }

    @Test
    fun `ALL_TIME_END matches Python constant`() {
        assertEquals(LocalDateTime.of(2040, 12, 31, 0, 0), TimeRangeConstraint.ALL_TIME_END)
    }

    @Test
    fun `allTime spans the full range`() {
        val t = TimeRangeConstraint.allTime()
        assertEquals(TimeRangeConstraint.ALL_TIME_BEGIN, t.startTime)
        assertEquals(TimeRangeConstraint.ALL_TIME_END, t.endTime)
    }

    @Test
    fun `end after ALL_TIME_END throws`() {
        assertFailsWith<UnableToSatisfyQueryError> {
            TimeRangeConstraint(
                startTime = dt(2026, 1, 1),
                endTime = dt(2050, 1, 1),
            )
        }
    }

    @Test
    fun `isSubsetOf detects containment`() {
        val outer = TimeRangeConstraint(dt(2026, 1, 1), dt(2026, 12, 31))
        val inner = TimeRangeConstraint(dt(2026, 3, 1), dt(2026, 6, 30))
        assertTrue(inner.isSubsetOf(outer))
        assertFalse(outer.isSubsetOf(inner))
    }

    @Test
    fun `intersection of disjoint ranges is empty`() {
        val a = TimeRangeConstraint(dt(2026, 1, 1), dt(2026, 6, 30))
        val b = TimeRangeConstraint(dt(2027, 1, 1), dt(2027, 6, 30))
        val i = a.intersection(b)
        assertEquals(TimeRangeConstraint.emptyTime(), i)
    }

    @Test
    fun `intersection of overlapping ranges keeps overlap`() {
        val a = TimeRangeConstraint(dt(2026, 1, 1), dt(2026, 6, 30))
        val b = TimeRangeConstraint(dt(2026, 3, 1), dt(2026, 12, 31))
        val i = a.intersection(b)
        assertEquals(dt(2026, 3, 1), i.startTime)
        assertEquals(dt(2026, 6, 30), i.endTime)
    }

    @Test
    fun `toString uses iso bracket notation`() {
        val t = TimeRangeConstraint(dt(2026, 1, 1), dt(2026, 12, 31))
        assertEquals("[2026-01-01T00:00, 2026-12-31T00:00]", t.toString())
    }
}
