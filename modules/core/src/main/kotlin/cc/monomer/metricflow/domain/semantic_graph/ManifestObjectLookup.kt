package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.common.time.TimeSpineSource
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.semantic_graph.lookup.ModelObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.lookup.SimpleMetricModelObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInput

/**
 * Manifest-level lookup tuned for fast initialization of the semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/lookups/manifest_object_lookup.py::ManifestObjectLookup`.
 *
 * Python comment on motivation: "These streamlined / minimal lookups were
 * added to use for initializing the semantic graph as the current lookup
 * classes have significant initialization times relative to initialization
 * time of the semantic-graph-based resolver." This Kotlin port preserves the
 * same indexes; the eager-evaluation pattern matches `cached_property`
 * semantics.
 *
 * Indexes maintained:
 *
 * - [simpleMetricModelLookups] / [simpleMetricExclusiveModelLookups] — models split by
 *   "has simple metrics?".
 * - [modelObjectLookups] — concatenation of both, in stable order.
 * - [modelIdToLookup] / [modelIdToSimpleMetricModelLookup] — id-keyed views.
 * - [simpleMetricNameToInput] — simple-metric-name to its [SimpleMetricInput].
 * - [entityNameToModelLookups] / [entityNameToModelIds] — reverse lookup by entity.
 * - [minTimeGrainInTimeSpine] / [minTimeGrainUsedInModels] / [expandedTimeGrains] — time
 *   grain extrema and the set of custom grains.
 */
class ManifestObjectLookup(val semanticManifest: SemanticManifest) {

    /** Mapping of time-grain to its standard time-spine source. */
    val timeSpineSources: Map<TimeGranularity, TimeSpineSource> =
        TimeSpineSource.buildStandardTimeSpineSources(semanticManifest)

    /** Mapping of custom-grain name to [ExpandedTimeGranularity]. */
    val customGranularities: Map<String, ExpandedTimeGranularity> =
        TimeSpineSource.buildCustomGranularities(timeSpineSources.values)

    /** Semantic models in declaration order. */
    val semanticModels: List<SemanticModel> = semanticManifest.semanticModels

    /** Aux: model-name -> list of simple metrics on that model. */
    private val modelNameToSimpleMetrics: Map<String, List<Metric>> = buildMap<String, MutableList<Metric>> {
        for (metric in semanticManifest.metrics) {
            if (metric.type == MetricType.SIMPLE) {
                val params = metric.typeParams.metricAggregationParams
                    ?: throw MetricFlowInternalError("A simple metric is missing metric_aggregation_params: $metric")
                getOrPut(params.semanticModel) { mutableListOf() }.add(metric)
            }
        }
    }

    private val semanticModelAndSimpleMetricsPairs: List<Pair<SemanticModel, List<Metric>>> =
        semanticManifest.semanticModels.map { it to (modelNameToSimpleMetrics[it.name] ?: emptyList()) }

    /** Per-model lookups for the models that have simple metrics. */
    val simpleMetricModelLookups: List<SimpleMetricModelObjectLookup> =
        semanticModelAndSimpleMetricsPairs
            .filter { (_, simpleMetrics) -> simpleMetrics.isNotEmpty() }
            .map { (model, simpleMetrics) -> SimpleMetricModelObjectLookup(model, simpleMetrics) }

    /** Per-model lookups for the models that have no simple metrics. */
    val simpleMetricExclusiveModelLookups: List<ModelObjectLookup> =
        semanticModelAndSimpleMetricsPairs
            .filter { (_, simpleMetrics) -> simpleMetrics.isEmpty() }
            .map { (model, _) -> ModelObjectLookup(model) }

    /** All per-model lookups. */
    val modelObjectLookups: List<ModelObjectLookup> =
        simpleMetricModelLookups + simpleMetricExclusiveModelLookups

    /** Model-id-keyed view of [modelObjectLookups]. */
    val modelIdToLookup: Map<SemanticModelId, ModelObjectLookup> =
        modelObjectLookups.associateBy { it.modelId }

    /** Model-id-keyed view of [simpleMetricModelLookups]. */
    val modelIdToSimpleMetricModelLookup: Map<SemanticModelId, SimpleMetricModelObjectLookup> =
        simpleMetricModelLookups.associateBy { it.modelId }

    /** Mapping from simple-metric name to its [SimpleMetricInput]. Sorted by name (Python parity). */
    val simpleMetricNameToInput: Map<String, SimpleMetricInput> = run {
        val collected = LinkedHashMap<String, SimpleMetricInput>()
        for (lookup in simpleMetricModelLookups) {
            for ((_, inputs) in lookup.aggregationConfigurationToSimpleMetricInputs) {
                for (input in inputs) {
                    collected[input.name] = input
                }
            }
        }
        collected.toSortedMap().toMap(LinkedHashMap())
    }

    /** Mapping from entity name to the model lookups that contain it. */
    val entityNameToModelLookups: Map<String, OrderedSet<ModelObjectLookup>> = run {
        val collected = LinkedHashMap<String, MutableOrderedSet<ModelObjectLookup>>()
        for ((_, lookup) in modelIdToLookup) {
            for (entity in lookup.semanticModel.entities) {
                collected.getOrPut(entity.name) { MutableOrderedSet() }.add(lookup)
            }
        }
        collected.mapValues { FrozenOrderedSet(it.value) }
    }

    /** Mapping from entity name to the IDs of the models that contain it. */
    val entityNameToModelIds: Map<String, OrderedSet<SemanticModelId>> =
        entityNameToModelLookups.mapValues { (_, lookups) ->
            FrozenOrderedSet(lookups.map { it.modelId })
        }

    private val metricNameToMetric: Map<String, Metric> =
        semanticManifest.metrics.associateBy { it.name }

    /** Look up a metric by name. Throws if not present. */
    fun getMetric(metricName: String): Metric =
        metricNameToMetric[metricName]
            ?: throw NoSuchElementException(
                "An object with the given name is not known: value_type=metric name=$metricName " +
                    "known_names=${metricNameToMetric.keys}",
            )

    /** Iterate over all known metrics. */
    fun getMetrics(): Iterable<Metric> = metricNameToMetric.values

    /** Smallest grain configured in the time spine, or `null` when no spine is configured. */
    val minTimeGrainInTimeSpine: TimeGranularity?
        get() = timeSpineSources.keys.minOrNull()

    /** Smallest grain used by any time dimension across all models, if any. */
    val minTimeGrainUsedInModels: TimeGranularity? get() = modelObjectLookups
        .flatMap { it.timeDimensionNameToGrain.values }
        .minOrNull()

    /** Custom (expanded) grains as configured in the time spine. */
    val expandedTimeGrains: List<ExpandedTimeGranularity>
        get() = customGranularities.values.toList()
}
