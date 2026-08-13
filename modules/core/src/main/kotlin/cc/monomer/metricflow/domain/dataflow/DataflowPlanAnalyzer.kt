package cc.monomer.metricflow.domain.dataflow

import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet

/**
 * Higher-order analysis utilities over a [DataflowPlan].
 *
 * Port of `metricflow.dataflow.dataflow_plan_analyzer.DataflowPlanAnalyzer`.
 *
 * The only public entry point is [findCommonBranches] — used by the W9b/optimizer pipeline to
 * fold shared sub-DAGs into CTEs.
 */
object DataflowPlanAnalyzer {

    /** Guardrail against non-converging common-branch detection. */
    const val MAX_COMMON_BRANCH_ITERATIONS: Int = 10

    /**
     * Find nodes that represent **common branches** in the plan DAG.
     *
     * Port of Python `DataflowPlanAnalyzer.find_common_branches`. Traversal starts at the sink
     * and repeatedly finds shared upstream branches. If a common branch has deeper common
     * sub-branches, those are included as well. Returns the "largest" shared roots at each
     * level of structure.
     */
    fun findCommonBranches(dataflowPlan: DataflowPlan): OrderedSet<DataflowPlanNode> {
        val allCommonBranches = MutableOrderedSet<DataflowPlanNode>()
        var branchRootsToScan: OrderedSet<DataflowPlanNode> =
            MutableOrderedSet<DataflowPlanNode>().apply { add(dataflowPlan.sinkNode) }

        repeat(MAX_COMMON_BRANCH_ITERATIONS) {
            val largestCommon = findLargestCommonBranches(branchRootsToScan)
            if (largestCommon.isEmpty()) return allCommonBranches
            allCommonBranches.addAll(largestCommon)
            branchRootsToScan = largestCommon
        }
        return allCommonBranches
    }

    private fun findLargestCommonBranches(
        nodes: OrderedSet<DataflowPlanNode>,
    ): OrderedSet<DataflowPlanNode> {
        val counter = CountDataflowNodeVisitor()
        for (node in nodes) node.accept(counter)

        val commonNodes = MutableOrderedSet<DataflowPlanNode>()
        for ((node, count) in counter.nodeCounts) {
            if (count > 1) commonNodes.add(node)
        }

        val largestCommonVisitor = FindLargestCommonBranchesVisitor(commonNodes)
        val commonBranches = MutableOrderedSet<DataflowPlanNode>()
        for (node in nodes) commonBranches.addAll(node.accept(largestCommonVisitor))
        return commonBranches
    }
}

/**
 * Counts how many times each node is reached while walking upstream from one or more roots.
 *
 * Port of `metricflow.dataflow.dataflow_plan_analyzer._CountDataflowNodeVisitor`.
 *
 * Counts **path reachability** (not unique node identity): a node reached via two parent paths
 * from the sink is counted twice. The post-order recursion mirrors Python exactly.
 */
private class CountDataflowNodeVisitor : DataflowPlanNodeVisitorWithDefaultHandler<Unit>() {
    private val counts: MutableMap<DataflowPlanNode, Int> = LinkedHashMap()

    val nodeCounts: Map<DataflowPlanNode, Int> get() = counts

    override fun defaultHandler(node: DataflowPlanNode) {
        for (parent in node.parentNodes) parent.accept(this)
        counts[node] = (counts[node] ?: 0) + 1
    }
}

/**
 * Given a set of common nodes, return only the **largest** common branches.
 *
 * Port of `metricflow.dataflow.dataflow_plan_analyzer._FindLargestCommonBranchesVisitor`.
 * Example: for `A → B → C → D` and `B → C → D`, both `B → C → D` and `C → D` are common. The
 * visitor returns `B` (or whatever is closest to the sink on each path) by stopping at the
 * first common node encountered while descending toward parents.
 */
private class FindLargestCommonBranchesVisitor(
    private val commonNodes: OrderedSet<DataflowPlanNode>,
) : DataflowPlanNodeVisitorWithDefaultHandler<OrderedSet<DataflowPlanNode>>() {

    override fun defaultHandler(node: DataflowPlanNode): OrderedSet<DataflowPlanNode> {
        if (node in commonNodes) return FrozenOrderedSet(listOf(node))
        val roots = MutableOrderedSet<DataflowPlanNode>()
        for (parent in node.parentNodes) roots.addAll(parent.accept(this))
        return roots
    }
}
