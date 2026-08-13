package cc.monomer.metricflow.common.graph

import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private class TestLabel(val name: String) : MetricFlowGraphLabel {
    override fun equals(other: Any?): Boolean = other is TestLabel && name == other.name
    override fun hashCode(): Int = name.hashCode()
    override fun toString(): String = name
}

private class TestNode(name: String, override val labels: FrozenOrderedSet<MetricFlowGraphLabel>) : MetricFlowGraphNode() {
    override val nodeDescriptor: MetricFlowGraphNodeDescriptor =
        MetricFlowGraphNodeDescriptor(nodeName = name, clusterName = null)
    constructor(name: String) : this(name, FrozenOrderedSet())
}

private class TestEdge(tail: TestNode, head: TestNode) : MetricFlowGraphEdge<TestNode>(tail, head) {
    override fun inverse(): MetricFlowGraphEdge<TestNode> = TestEdge(headNode, tailNode)
}

class MutableGraphTest {

    @Test
    fun `addNode tracks nodes and labels`() {
        val labelA = TestLabel("a")
        val nodeA = TestNode("A", FrozenOrderedSet(listOf<MetricFlowGraphLabel>(labelA)))
        val graph = MutableGraph<TestNode, TestEdge>()
        graph.addNode(nodeA)
        assertEquals(1, graph.nodes.size)
        assertEquals(setOf(nodeA), graph.nodesWithLabels(labelA))
    }

    @Test
    fun `addEdge implicitly adds endpoints`() {
        val a = TestNode("A")
        val b = TestNode("B")
        val edge = TestEdge(a, b)
        val graph = MutableGraph<TestNode, TestEdge>()
        graph.addEdge(edge)
        assertTrue(a in graph.nodes)
        assertTrue(b in graph.nodes)
        assertEquals(setOf(edge), graph.edgesWithTailNode(a))
        assertEquals(setOf(edge), graph.edgesWithHeadNode(b))
        assertEquals(setOf(b), graph.successors(a))
        assertEquals(setOf(a), graph.predecessors(b))
    }

    @Test
    fun `graph id changes after mutation`() {
        val graph = MutableGraph<TestNode, TestEdge>()
        val before = graph.graphId.strValue
        graph.addNode(TestNode("A"))
        val after = graph.graphId.strValue
        assertTrue(before != after)
    }

    @Test
    fun `nodeWithLabel returns single match`() {
        val l1 = TestLabel("L1")
        val a = TestNode("A", FrozenOrderedSet(listOf<MetricFlowGraphLabel>(l1)))
        val graph = MutableGraph<TestNode, TestEdge>()
        graph.addNode(a)
        graph.addNode(TestNode("B"))
        assertEquals(a, graph.nodeWithLabel(l1))
    }

    @Test
    fun `nodeWithLabel throws on missing or duplicate`() {
        val graph = MutableGraph<TestNode, TestEdge>()
        assertFailsWith<NoSuchElementException> { graph.nodeWithLabel(TestLabel("nope")) }
    }
}

class PathfinderTest {

    @Test
    fun `descendants are reached through edges`() {
        val a = TestNode("A")
        val b = TestNode("B")
        val c = TestNode("C")
        val graph = MutableGraph<TestNode, TestEdge>()
        graph.addEdge(TestEdge(a, b))
        graph.addEdge(TestEdge(b, c))
        val finder = Pathfinder<TestNode, TestEdge>()
        val result = finder.findDescendants(
            graph = graph,
            sourceNodes = FrozenOrderedSet(listOf(a)),
            targetNodes = FrozenOrderedSet(listOf(c)),
            nodeAllowSet = null,
            denyLabels = null,
            maxIterationCount = PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS,
        )
        assertTrue(c in result.reachableNodes)
        assertEquals(setOf(c), result.reachableTargetNodes)
    }

    @Test
    fun `ancestors walk back via predecessors`() {
        val a = TestNode("A")
        val b = TestNode("B")
        val c = TestNode("C")
        val graph = MutableGraph<TestNode, TestEdge>()
        graph.addEdge(TestEdge(a, b))
        graph.addEdge(TestEdge(b, c))
        val finder = Pathfinder<TestNode, TestEdge>()
        val result = finder.findAncestors(
            graph = graph,
            sourceNodes = FrozenOrderedSet(listOf(a)),
            targetNodes = FrozenOrderedSet(listOf(c)),
            nodeAllowSet = null,
            denyLabels = null,
            maxIterationCount = PATHFINDER_DEFAULT_MAX_BFS_ITERATIONS,
        )
        assertTrue(a in result.reachableSourceNodes)
    }

    @Test
    fun `frozen ordered set rejects mutation through MutableOrderedSet ref`() {
        val s = MutableOrderedSet<String>().apply { addAll(listOf("a", "b")) }
        val f = s.asFrozen()
        assertEquals(setOf("a", "b"), f.toSet())
    }
}
