package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.util.cache.ResultCache
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup

/**
 * Resolve the evaluation level of a metric in the dependency graph.
 *
 * Port of `metricflow.metric_evaluation.passthrough.me_level_resolver.MetricEvaluationLevelResolver`.
 *
 * Evaluation levels:
 *
 * - Simple / cumulative / conversion metrics → level `0`.
 * - Ratio / derived metrics → `max(input_levels) + 1`.
 *
 * For example, `bookings_per_listing` depends on `bookings` and `listings`. If
 * those inputs are level `0`, then `bookings_per_listing` is level `1`.
 */
class MetricEvaluationLevelResolver(
    private val manifestObjectLookup: ManifestObjectLookup,
) {

    private val cache: ResultCache<String, Int> = ResultCache()

    /** Return the evaluation level for [metricName]. */
    fun resolveEvaluationLevel(metricName: String): Int {
        val cacheEntry = cache.get(metricName)
        if (cacheEntry != null) return cacheEntry.value
        return cache.setAndGet(metricName, computeEvaluationLevel(metricName))
    }

    private fun computeEvaluationLevel(metricName: String): Int {
        val metric = manifestObjectLookup.getMetric(metricName)
        return when (metric.type) {
            MetricType.SIMPLE, MetricType.CUMULATIVE, MetricType.CONVERSION -> 0
            MetricType.RATIO, MetricType.DERIVED -> {
                // A derived metric can list the same input metric multiple times via aliases,
                // so deduplicate by name.
                val inputMetricNames = metric.inputMetrics.map { it.name }.toSet()
                if (inputMetricNames.isEmpty()) {
                    throw MetricFlowInternalError(
                        "Expected ratio or derived metrics to define input metrics: " +
                            "metricName=$metricName metricType=${metric.type}",
                    )
                }
                inputMetricNames.maxOf { resolveEvaluationLevel(it) } + 1
            }
        }
    }
}
