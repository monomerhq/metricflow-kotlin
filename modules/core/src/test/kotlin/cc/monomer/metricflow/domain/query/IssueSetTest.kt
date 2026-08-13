package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.issue.MetricFlowQueryResolutionIssueSet
import cc.monomer.metricflow.domain.query.issue.parsing.NoMetricOrGroupByIssue
import cc.monomer.metricflow.domain.query.issue.parsing.StringInputParsingIssue
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueSetTest {

    @Test
    fun `EMPTY has no errors and no issues`() {
        assertFalse(MetricFlowQueryResolutionIssueSet.EMPTY.hasErrors)
        assertFalse(MetricFlowQueryResolutionIssueSet.EMPTY.hasIssues)
        assertEquals(0, MetricFlowQueryResolutionIssueSet.EMPTY.size)
    }

    @Test
    fun `fromIssue surfaces hasErrors`() {
        val issue = StringInputParsingIssue.fromParameters("bookings(")
        val set = MetricFlowQueryResolutionIssueSet.fromIssue(issue)
        assertTrue(set.hasErrors)
        assertTrue(set.hasIssues)
        assertEquals(1, set.errors.size)
        assertEquals(1, set.size)
    }

    @Test
    fun `merge concatenates issues`() {
        val a = MetricFlowQueryResolutionIssueSet.fromIssue(StringInputParsingIssue.fromParameters("x"))
        val b = MetricFlowQueryResolutionIssueSet.fromIssue(StringInputParsingIssue.fromParameters("y"))
        val merged = a.merge(b)
        assertEquals(2, merged.size)
    }

    @Test
    fun `addIssue appends to the issue list`() {
        val first = StringInputParsingIssue.fromParameters("alpha")
        val second = NoMetricOrGroupByIssue.fromParameters(MetricFlowQueryResolutionPath.EMPTY)
        val set = MetricFlowQueryResolutionIssueSet.fromIssue(first).addIssue(second)
        assertEquals(2, set.size)
    }

    @Test
    fun `withPathPrefix re-anchors every contained issue`() {
        val issue = StringInputParsingIssue.fromParameters("foo")
        val set = MetricFlowQueryResolutionIssueSet.fromIssue(issue)
        val prefixed = set.withPathPrefix(MetricFlowQueryResolutionPath.EMPTY)
        assertEquals(1, prefixed.size)
    }
}
