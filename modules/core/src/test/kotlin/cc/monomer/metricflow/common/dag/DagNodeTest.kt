package cc.monomer.metricflow.common.dag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private class SourceNode : DagNode<SourceNode>(parentNodes = emptyList()) {
    override val description: String = "source"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_READ_SQL_SOURCE_ID_PREFIX
}

private class FilterNode(parents: List<SourceNode>) : DagNode<SourceNode>(parents) {
    override val description: String = "filter"
    override fun idPrefix(): IdPrefix = StaticIdPrefix.DATAFLOW_NODE_WHERE_CONSTRAINT_ID_PREFIX
}

class DagNodeTest {

    @Test
    fun `each node gets a unique id derived from its prefix`() {
        SequentialIdGenerator.reset()
        val a = SourceNode()
        val b = SourceNode()
        assertNotEquals(a.nodeId, b.nodeId)
        assertTrue(a.nodeId.idStr.startsWith("rss_"))
    }

    @Test
    fun `parent nodes are exposed`() {
        SequentialIdGenerator.reset()
        val src = SourceNode()
        val flt = FilterNode(listOf(src))
        assertEquals(listOf(src), flt.parentNodes)
    }

    @Test
    fun `displayed properties include description and node id`() {
        SequentialIdGenerator.reset()
        val src = SourceNode()
        val keys = src.displayedProperties.map { it.key }
        assertTrue("description" in keys)
        assertTrue("node_id" in keys)
    }

    @Test
    fun `dag text formatter wraps node in xml-like tags`() {
        SequentialIdGenerator.reset()
        val src = SourceNode()
        val text = MetricFlowDagTextFormatter().dagComponentToText(src)
        assertTrue(text.startsWith("<SourceNode>"))
        assertTrue(text.endsWith("</SourceNode>"))
        assertTrue(text.contains("description = 'source'"))
    }

    @Test
    fun `dag text formatter nests parents`() {
        SequentialIdGenerator.reset()
        val src = SourceNode()
        val flt = FilterNode(listOf(src))
        val text = MetricFlowDagTextFormatter().dagComponentToText(flt)
        assertTrue(text.startsWith("<FilterNode>"))
        assertTrue(text.contains("<SourceNode>"))
        // Source node should be indented under filter.
        assertTrue(text.contains("    <SourceNode>"))
    }

    @Test
    fun `dag id round trips`() {
        SequentialIdGenerator.reset()
        val id = DagId.fromIdPrefix(StaticIdPrefix.DATAFLOW_PLAN_PREFIX)
        assertTrue(id.idStr.startsWith("dfp_"))
    }
}
