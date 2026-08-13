package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElement
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryNode

/**
 * Select the candidate query nodes that best satisfy the required query elements.
 *
 * Port of
 * `metricflow.metric_evaluation.passthrough.query_set_selector.BestMetricQuerySetSelector`.
 *
 * The greedy selection prefers candidates that cover the largest set of
 * remaining required elements — this maximises passthrough reuse and reduces
 * the number of joins in the final query.
 */
class BestMetricQuerySetSelector(
    private val queryElementToLevel: Map<MetricQueryElement, Int>,
) {

    /** Map each required input element to a candidate query node. */
    fun findBestQueries(
        desiredQueryElements: OrderedSet<MetricQueryElement>,
        candidateInputNodes: List<MetricQueryNode>,
    ): FindBestQuerySetResult {
        // The most nested derived metric likely has the most passthrough specs available, so try
        // to fulfill it first — passthrough metrics from that node can fulfill other elements too.
        val remaining: MutableOrderedSet<MetricQueryElement> = MutableOrderedSet(
            desiredQueryElements
                .toList()
                .sortedByDescending { queryElementLevel(it) },
        )

        val inputNodeToFulfilled = LinkedHashMap<MetricQueryNode, OrderedSet<MetricQueryElement>>()

        while (remaining.isNotEmpty()) {
            val selected = selectQueryWithBestCoverage(remaining, candidateInputNodes)
            if (selected == null) {
                return FindBestQuerySetResult(
                    remainingDesiredQueryElements = FrozenOrderedSet(remaining),
                    inputQueryNodeToFulfilledQueryElements = inputNodeToFulfilled,
                )
            }
            val fulfilled = FrozenOrderedSet(
                remaining.toList().intersect(selected.outputQueryElements.toSet()),
            )
            inputNodeToFulfilled[selected] = fulfilled
            remaining.removeAll(fulfilled.toSet())
        }

        return FindBestQuerySetResult(
            remainingDesiredQueryElements = FrozenOrderedSet(remaining),
            inputQueryNodeToFulfilledQueryElements = inputNodeToFulfilled,
        )
    }

    private fun queryElementLevel(element: MetricQueryElement): Int =
        queryElementToLevel[element]
            ?: throw MetricFlowInternalError(
                "Missing evaluation level for query element: $element; " +
                    "known=${queryElementToLevel.keys}",
            )

    private fun selectQueryWithBestCoverage(
        desired: OrderedSet<MetricQueryElement>,
        candidates: List<MetricQueryNode>,
    ): MetricQueryNode? {
        if (candidates.isEmpty()) return null
        val desiredSet = desired.toSet()

        var best: MetricQueryNode? = null
        var bestScore: Pair<Int, Int>? = null
        for (candidate in candidates) {
            val output = candidate.outputQueryElements.toSet()
            val fulfilledCount = output.count { it in desiredSet }
            val score = fulfilledCount to output.size
            if (bestScore == null || score.compareTo(bestScore) > 0) {
                bestScore = score
                best = candidate
            }
        }

        val selected = best ?: return null
        val bestFulfilled = selected.outputQueryElements.toSet().count { it in desiredSet }
        if (bestFulfilled == 0) return null
        return selected
    }

    private fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int {
        val first = first.compareTo(other.first)
        if (first != 0) return first
        return second.compareTo(other.second)
    }
}

/**
 * Result of [BestMetricQuerySetSelector.findBestQueries].
 *
 * Port of `FindBestQuerySetResult`.
 */
data class FindBestQuerySetResult(
    val remainingDesiredQueryElements: OrderedSet<MetricQueryElement>,
    val inputQueryNodeToFulfilledQueryElements: Map<MetricQueryNode, OrderedSet<MetricQueryElement>>,
)
