package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.common.errors.DuplicateMetricError
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.errors.MetricNotFoundError
import cc.monomer.metricflow.common.util.cache.ResultCache
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Tracks semantic information for metrics by linking them to semantic models.
 *
 * Port of `metricflow_semantics/model/semantics/metric_lookup.py::MetricLookup`.
 *
 * Eagerly indexes the manifest's metrics by [MetricReference] and rejects duplicates (Python
 * raises `DuplicateMetricError`). Once built, it answers:
 *
 * - `getMetric(ref)` / `getMetrics(refs)` — fetch metric records.
 * - `metricReferences` — sorted list of every metric reference in the manifest.
 * - `metricInputs(metric, includeConversionMetricInput)` — static helper enumerating the input
 *   metrics that a complex metric depends on.
 * - `getDerivedFromSemanticModels(ref)` — set of models whose simple metrics contribute to a
 *   metric.
 *
 * ### Scope note
 *
 * Python additionally provides `get_group_by_items_for_distinct_values_query`,
 * `get_common_group_by_items`, `get_aggregation_time_dimension_specs`, and
 * `get_min_queryable_time_granularity`. These all depend on the semantic-graph resolver
 * (`GroupByItemSetResolver`) and the `simple_metric_name_to_input` map produced by
 * `ManifestObjectLookup`, both of which belong to W7b. They will land alongside that wave.
 */
class MetricLookup(
    semanticManifest: SemanticManifest,
) {

    private val metrics: MutableMap<MetricReference, Metric> = LinkedHashMap()

    private val derivedFromSemanticModelsCache: ResultCache<MetricReference, List<SemanticModelReference>> =
        ResultCache()

    init {
        for (metric in semanticManifest.metrics) {
            val metricReference = MetricReference(metric.name)
            if (metricReference in metrics) {
                throw DuplicateMetricError(
                    "A duplicate metric was found in the manifest: " +
                        "${metrics[metricReference]?.name} vs ${metric.name}",
                )
            }
            metrics[metricReference] = metric
        }
    }

    /** Resolve [metricReference] to the [Metric] record. Throws [MetricNotFoundError] if unknown. */
    fun getMetric(metricReference: MetricReference): Metric =
        metrics[metricReference]
            ?: throw MetricNotFoundError("The given metric is not known: ${metricReference.elementName}")

    /** Resolve a list of metric references to [Metric] records in the same order. */
    fun getMetrics(metricReferences: Iterable<MetricReference>): List<Metric> =
        metricReferences.map { getMetric(it) }

    /**
     * Sorted, deduplicated list of every metric reference defined in the manifest. Python returns a
     * `FrozenOrderedSet`; we use `List` because Kotlin doesn't ship one and call sites need
     * insertion-order iteration plus uniqueness, which the constructor already guarantees.
     */
    val metricReferences: List<MetricReference>
        get() = metrics.keys.sorted()

    /**
     * Return the set of semantic models whose simple metrics contribute to the given complex
     * metric. The result is ordered (sorted by semantic-model name to mirror Python's
     * `MutableOrderedSet` semantics with sorted iteration) and deduplicated.
     */
    fun getDerivedFromSemanticModels(metricReference: MetricReference): List<SemanticModelReference> {
        val cached = derivedFromSemanticModelsCache.get(metricReference)
        if (cached != null) return cached.value

        val metric = getMetric(metricReference)
        val metricInputs = metricInputs(metric, includeConversionMetricInput = true)

        val result: MutableList<SemanticModelReference> = mutableListOf()
        if (metricInputs.isEmpty()) {
            val metricAggregationParams = metric.typeParams.metricAggregationParams
                ?: throw MetricFlowInternalError(
                    "Expected `metric_aggregation_params` to be set: metric=${metric.name}",
                )
            result.add(SemanticModelReference(metricAggregationParams.semanticModel))
        } else {
            val seen: MutableSet<SemanticModelReference> = LinkedHashSet()
            for (metricInput in metricInputs) {
                for (ref in getDerivedFromSemanticModels(MetricReference(metricInput.name))) {
                    if (seen.add(ref)) result.add(ref)
                }
            }
        }

        val immutable = result.toList()
        return derivedFromSemanticModelsCache.setAndGet(metricReference, immutable)
    }

    companion object {
        /**
         * Return the metric inputs for the given metric.
         *
         * - SIMPLE → no inputs.
         * - CUMULATIVE → optional `cumulative_type_params.metric`.
         * - RATIO → numerator and denominator.
         * - DERIVED → `metrics` list.
         * - CONVERSION → base metric and (optionally) conversion metric.
         */
        fun metricInputs(metric: Metric, includeConversionMetricInput: Boolean): List<MetricInput> {
            val out = mutableListOf<MetricInput>()
            when (metric.type) {
                MetricType.SIMPLE -> Unit
                MetricType.CUMULATIVE -> {
                    val cumulativeTypeParams = metric.typeParams.cumulativeTypeParams
                        ?: throw MetricFlowInternalError(
                            "Expected `cumulative_type_params` to be set for a cumulative metric: " +
                                "complex_metric=${metric.name}",
                        )
                    val inputMetric = cumulativeTypeParams.metric
                        ?: throw MetricFlowInternalError(
                            "Expected `metric` to be set for a cumulative metric: complex_metric=${metric.name}",
                        )
                    out.add(inputMetric)
                }
                MetricType.RATIO -> {
                    val numerator = metric.typeParams.numerator
                        ?: throw MetricFlowInternalError(
                            "Expected `numerator` to be set for a ratio metric: complex_metric=${metric.name}",
                        )
                    val denominator = metric.typeParams.denominator
                        ?: throw MetricFlowInternalError(
                            "Expected `denominator` to be set for a ratio metric: complex_metric=${metric.name}",
                        )
                    out.add(numerator)
                    out.add(denominator)
                }
                MetricType.CONVERSION -> {
                    val conversionTypeParams = metric.typeParams.conversionTypeParams
                        ?: throw MetricFlowInternalError(
                            "Expected `conversion_type_params` to be set for a conversion metric: " +
                                "complex_metric=${metric.name}",
                        )
                    val baseMetric = conversionTypeParams.baseMetric
                        ?: throw MetricFlowInternalError(
                            "Expected `base_metric` to be set for a conversion metric: " +
                                "complex_metric=${metric.name}",
                        )
                    out.add(baseMetric)
                    if (includeConversionMetricInput) {
                        val conversionMetric = conversionTypeParams.conversionMetric
                            ?: throw MetricFlowInternalError(
                                "Expected `conversion_metric` to be set for a conversion metric: " +
                                    "complex_metric=${metric.name}",
                            )
                        out.add(conversionMetric)
                    }
                }
                MetricType.DERIVED -> {
                    val metrics = metric.typeParams.metrics
                    if (metrics.isNullOrEmpty()) {
                        throw MetricFlowInternalError(
                            "Expected `metrics` to be set for a derived metric: derived_metric=${metric.name}",
                        )
                    }
                    out.addAll(metrics)
                }
            }
            return out
        }
    }
}
