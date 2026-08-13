package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricAggregationParams
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.MetricTypeParams
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection

/**
 * Helper used by several transformation rules to build / look up simple metrics whose shape is
 * derived from a measure.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/measure_to_metric_transformation_pieces/measure_features_to_metric_name.py::MeasureFeaturesToMetricNameMapper`.
 *
 * The Python implementation mutates the manifest's metric list in-place and threads an internal
 * cache `_metric_name_dict` to avoid duplicate work. Our Kotlin shape returns an updated
 * manifest plus the resolved metric name, and the caller is responsible for cache-keying calls
 * across the rule's metric loop.
 */
internal class MeasureFeaturesToMetricNameMapper {

    private data class Key(
        val measureName: String,
        val fillNullsWith: Int?,
        val joinToTimespine: Boolean,
    )

    private val storedNames: MutableMap<Key, String> = mutableMapOf()

    /**
     * Result of [getOrCreateMetricForMeasure]: the metric name and the (possibly modified)
     * manifest. If a new metric was appended, [manifest] has it on the end of `metrics`.
     */
    data class Resolution(val metricName: String, val manifest: SemanticManifest)

    fun getOrCreateMetricForMeasure(
        manifest: SemanticManifest,
        modelName: String,
        measure: Measure,
        measureInputFilters: WhereFilterIntersection?,
        fillNullsWith: Int?,
        joinToTimespine: Boolean,
        existingMetricNames: MutableSet<String>,
    ): Resolution {
        val key = Key(measure.name, fillNullsWith, joinToTimespine)
        storedNames[key]?.let { return Resolution(it, manifest) }

        val built = buildMetricFromMeasureConfiguration(
            measure = measure,
            semanticModelName = modelName,
            fillNullsWith = fillNullsWith,
            joinToTimespine = joinToTimespine,
            isPrivate = true,
            measureInputFilters = measureInputFilters,
        )

        val existing = findFunctionalCloneInManifest(built, manifest)
        val resolved = if (existing != null) {
            Resolution(existing.name, manifest)
        } else {
            val newName = generateNewMetricName(
                measureName = measure.name,
                fillNullsWith = fillNullsWith,
                joinToTimespine = joinToTimespine,
                existingMetricNames = existingMetricNames,
            )
            val newMetric = built.copy(name = newName)
            existingMetricNames.add(newName)
            Resolution(newName, manifest.copy(metrics = manifest.metrics + newMetric))
        }

        storedNames[key] = resolved.metricName
        return resolved
    }

    private fun generateNewMetricName(
        measureName: String,
        fillNullsWith: Int?,
        joinToTimespine: Boolean,
        existingMetricNames: Set<String>,
    ): String {
        val parts = mutableListOf(measureName)
        if (fillNullsWith != null) {
            val piece = if (fillNullsWith >= 0) fillNullsWith.toString() else "neg_${-fillNullsWith}"
            parts.add("fill_nulls_with_$piece")
        }
        if (joinToTimespine) parts.add("join_to_timespine")
        val base = parts.joinToString("_")
        var name = base
        var count = 1
        while (name in existingMetricNames) {
            name = "${base}_$count"
            count++
        }
        return name
    }

    private fun findFunctionalCloneInManifest(metric: Metric, manifest: SemanticManifest): Metric? {
        for (existing in manifest.metrics) {
            if (metricsEquivalent(metric, existing)) return existing
        }
        return null
    }

    private fun metricsEquivalent(search: Metric, candidate: Metric): Boolean {
        val sp = search.typeParams
        val cp = candidate.typeParams
        val baseMatches = search.type == candidate.type &&
            sp.window == cp.window &&
            sp.grainToDate == cp.grainToDate &&
            sp.metricAggregationParams == cp.metricAggregationParams &&
            sp.joinToTimespine == cp.joinToTimespine &&
            sp.fillNullsWith == cp.fillNullsWith &&
            sp.expr == cp.expr &&
            search.filter == candidate.filter &&
            search.timeGranularity == candidate.timeGranularity
        if (!baseMatches) return false
        // Python: "if manifest_metric.type_params.measure is not None and
        //         search_metric.type_params.measure != manifest_metric.type_params.measure: return False"
        if (cp.measure != null && sp.measure != cp.measure) return false
        return true
    }
}

/**
 * Build a SIMPLE metric whose shape comes from a measure. Equivalent to
 * `MeasureFeaturesToMetricNameMapper.build_metric_from_measure_configuration` in Python.
 *
 * Exposed at file-level (not on the mapper) so [CreateProxyMeasureRule] can call it without
 * threading a mapper instance.
 */
internal fun buildMetricFromMeasureConfiguration(
    measure: Measure,
    semanticModelName: String,
    fillNullsWith: Int?,
    joinToTimespine: Boolean,
    isPrivate: Boolean,
    measureInputFilters: WhereFilterIntersection?,
): Metric {
    val baseMetric = Metric(
        name = measure.name,
        type = MetricType.SIMPLE,
        typeParams = MetricTypeParams(isPrivate = isPrivate),
        description = measure.description,
        label = measure.label,
        config = measure.config,
        metadata = measure.metadata,
    )
    return applyMeasureFeaturesToSimpleMetric(
        measure = measure,
        semanticModelName = semanticModelName,
        metric = baseMetric,
        fillNullsWith = fillNullsWith,
        joinToTimespine = joinToTimespine,
        measureInputFilters = measureInputFilters,
    )
}

/**
 * Apply a measure's features onto a SIMPLE metric. Equivalent to
 * `MeasureFeaturesToMetricNameMapper.update_required_measure_features_in_simple_model` in
 * Python, but functional: returns a new [Metric] instead of mutating in place.
 *
 * Used by both [CreateProxyMeasureRule] (via [buildMetricFromMeasureConfiguration]) and
 * [FlattenSimpleMetricsWithMeasureInputsRule].
 */
internal fun applyMeasureFeaturesToSimpleMetric(
    measure: Measure,
    semanticModelName: String,
    metric: Metric,
    fillNullsWith: Int?,
    joinToTimespine: Boolean,
    measureInputFilters: WhereFilterIntersection?,
): Metric {
    check(metric.type == MetricType.SIMPLE) {
        "Attempted to set measure features on a non-simple metric: $metric"
    }
    if (metric.typeParams.metricAggregationParams != null) {
        // Already populated; skip — Python's "these values have already been set" early-return.
        return metric
    }

    var typeParams = metric.typeParams

    // We only set these if they are passed in explicitly so we can avoid overriding defaults.
    if (fillNullsWith != null) {
        typeParams = typeParams.copy(fillNullsWith = fillNullsWith)
    }
    if (joinToTimespine) {
        typeParams = typeParams.copy(joinToTimespine = true)
    }

    typeParams = typeParams.copy(
        metricAggregationParams = MetricAggregationParams(
            semanticModel = semanticModelName,
            agg = measure.agg,
            aggParams = measure.aggParams,
            aggTimeDimension = measure.aggTimeDimension,
            nonAdditiveDimension = measure.nonAdditiveDimension,
        ),
    )

    // Measures without an expr fall back to using the measure name as the column name.
    if (typeParams.expr == null) {
        typeParams = typeParams.copy(expr = measure.expr ?: measure.name)
    }

    // Combine measure-input filters with existing metric filters.
    val combinedFilters = buildList {
        measureInputFilters?.whereFilters?.let { addAll(it) }
        metric.filter?.whereFilters?.let { addAll(it) }
    }
    val newFilter = if (combinedFilters.isNotEmpty()) {
        WhereFilterIntersection(whereFilters = combinedFilters)
    } else metric.filter

    // SL-4257 legacy: artificial input measure for backward compatibility.
    val artificialMeasureInput = MetricInputMeasure(
        name = measure.name,
        filter = measureInputFilters,
        joinToTimespine = false,
        fillNullsWith = null,
    )
    typeParams = typeParams.copy(
        measure = artificialMeasureInput,
        inputMeasures = listOf(artificialMeasureInput),
    )

    return metric.copy(typeParams = typeParams, filter = newFilter)
}
