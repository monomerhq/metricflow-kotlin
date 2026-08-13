package cc.monomer.metricflow.domain.query

import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.NoMetricsGroupByItemSourceNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.QueryGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.SimpleMetricGroupByItemSourceNode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricFlowQueryResolutionPathTest {

    @Test
    fun `EMPTY path renders as expected`() {
        assertEquals("[Empty Path]", MetricFlowQueryResolutionPath.EMPTY.uiDescription)
    }

    @Test
    fun `fromPathItem yields a singleton path`() {
        val node = SimpleMetricGroupByItemSourceNode.create(
            simpleMetricReference = MetricReference("bookings"),
            metricInputLocation = null,
        )
        val path = MetricFlowQueryResolutionPath.fromPathItem(node)
        assertEquals(1, path.resolutionPathNodes.size)
        assertTrue(path.uiDescription.startsWith("[Resolve SimpleMetric"))
    }

    @Test
    fun `withPathPrefix prepends the path`() {
        val a = SimpleMetricGroupByItemSourceNode.create(MetricReference("a"), null)
        val b = SimpleMetricGroupByItemSourceNode.create(MetricReference("b"), null)
        val pathA = MetricFlowQueryResolutionPath.fromPathItem(a)
        val pathB = MetricFlowQueryResolutionPath.fromPathItem(b)
        val joined = pathB.withPathPrefix(pathA)
        assertEquals(listOf(a, b), joined.resolutionPathNodes)
    }

    @Test
    fun `query node renders with metrics`() {
        val a = SimpleMetricGroupByItemSourceNode.create(MetricReference("bookings"), null)
        val query = QueryGroupByItemResolutionNode.create(
            parentNodes = listOf(a),
            metricsInQuery = listOf(MetricReference("bookings")),
            whereFilterIntersection = WhereFilterIntersection(emptyList()),
        )
        assertTrue(query.uiDescription.contains("bookings"))
    }

    @Test
    fun `NoMetricsGroupByItemSourceNode has zero parents`() {
        val node = NoMetricsGroupByItemSourceNode.create()
        assertTrue(node.parentNodes.isEmpty())
    }
}
