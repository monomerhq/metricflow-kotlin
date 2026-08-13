package cc.monomer.metricflow.domain.manifest.transformation.rules

import cc.monomer.metricflow.domain.manifest.model.CumulativeTypeParams
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.transformation.SemanticManifestTransformRule
import org.slf4j.LoggerFactory

/**
 * Replaces measure inputs on cumulative and conversion metrics with metric inputs that point at
 * an auto-derived SIMPLE metric matching the original measure shape.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/replace_input_measures_with_simple_metrics_transformation.py::ReplaceInputMeasuresWithSimpleMetricsTransformationRule`.
 *
 * For cumulative metrics: when `type_params.measure` is set but `cumulative_type_params.metric`
 * is not, find (or create via [MeasureFeaturesToMetricNameMapper]) a matching SIMPLE metric and
 * populate `cumulative_type_params.metric` with a new [MetricInput].
 *
 * For conversion metrics: same logic for `base_measure` -> `base_metric` and `conversion_measure`
 * -> `conversion_metric`.
 *
 * The Python rule leaves the old `measure` reference in place for backward compatibility, so we
 * do too.
 *
 * The legacy filters from a measure input are NOT applied to the new SIMPLE metric — the complex
 * metric's MetricInput carries those instead.
 */
object ReplaceInputMeasuresWithSimpleMetricsTransformationRule : SemanticManifestTransformRule {
    private val log = LoggerFactory.getLogger(ReplaceInputMeasuresWithSimpleMetricsTransformationRule::class.java)

    override fun transformModel(semanticManifest: SemanticManifest): SemanticManifest {
        val mapper = MeasureFeaturesToMetricNameMapper()
        val existingNames: MutableSet<String> = semanticManifest.metrics.mapTo(mutableSetOf()) { it.name }
        var manifest = semanticManifest

        // Iterate the **original** metric list (by index/name snapshot). Python iterates the live
        // list; new metrics appended during this rule have a unique generated name and are never
        // processed as a target metric. We mirror that by iterating the names captured up-front.
        val targetNames = semanticManifest.metrics.map { it.name }
        for (name in targetNames) {
            val metric = manifest.metrics.firstOrNull { it.name == name } ?: continue
            manifest = when (metric.type) {
                MetricType.CUMULATIVE -> handleCumulative(metric, manifest, mapper, existingNames)
                MetricType.CONVERSION -> handleConversion(metric, manifest, mapper, existingNames)
                else -> manifest
            }
        }
        return manifest
    }

    private fun handleCumulative(
        metric: Metric,
        manifest: SemanticManifest,
        mapper: MeasureFeaturesToMetricNameMapper,
        existingNames: MutableSet<String>,
    ): SemanticManifest {
        val inputMeasure = metric.typeParams.measure ?: return manifest
        val ctp = metric.typeParams.cumulativeTypeParams ?: CumulativeTypeParams(metric = null)

        val build = buildMetricInput(
            mapper = mapper,
            inputMeasure = inputMeasure,
            inputMetric = ctp.metric,
            manifest = manifest,
            existingNames = existingNames,
        )

        val updatedCtp = if (build?.metricInput != null) {
            ctp.copy(metric = build.metricInput)
        } else if (metric.typeParams.cumulativeTypeParams == null) {
            ctp
        } else null

        val newManifest = build?.manifest ?: manifest
        if (updatedCtp == null) return newManifest

        return newManifest.replaceMetric(metric.name) {
            it.copy(typeParams = it.typeParams.copy(cumulativeTypeParams = updatedCtp))
        }
    }

    private fun handleConversion(
        metric: Metric,
        manifest: SemanticManifest,
        mapper: MeasureFeaturesToMetricNameMapper,
        existingNames: MutableSet<String>,
    ): SemanticManifest {
        val ctp = metric.typeParams.conversionTypeParams
        if (ctp == null) {
            log.warn(
                "Conversion metric {} has no conversion type params; " +
                    "skipping replacement on conversion metric. " +
                    "(This should also be caught by validations.)",
                metric.name,
            )
            return manifest
        }

        var current = manifest

        val baseBuild = buildMetricInput(
            mapper = mapper,
            inputMeasure = ctp.baseMeasure,
            inputMetric = ctp.baseMetric,
            manifest = current,
            existingNames = existingNames,
        )
        if (baseBuild != null) current = baseBuild.manifest

        val convBuild = buildMetricInput(
            mapper = mapper,
            inputMeasure = ctp.conversionMeasure,
            inputMetric = ctp.conversionMetric,
            manifest = current,
            existingNames = existingNames,
        )
        if (convBuild != null) current = convBuild.manifest

        if (baseBuild?.metricInput == null && convBuild?.metricInput == null) return current

        return current.replaceMetric(metric.name) { m ->
            val ctp2 = m.typeParams.conversionTypeParams!!
            val updated = ctp2.copy(
                baseMetric = baseBuild?.metricInput ?: ctp2.baseMetric,
                conversionMetric = convBuild?.metricInput ?: ctp2.conversionMetric,
            )
            m.copy(typeParams = m.typeParams.copy(conversionTypeParams = updated))
        }
    }

    private data class MetricInputBuild(val metricInput: MetricInput?, val manifest: SemanticManifest)

    private fun buildMetricInput(
        mapper: MeasureFeaturesToMetricNameMapper,
        inputMeasure: MetricInputMeasure?,
        inputMetric: MetricInput?,
        manifest: SemanticManifest,
        existingNames: MutableSet<String>,
    ): MetricInputBuild? {
        if (inputMeasure == null || inputMetric != null) return null
        val measureMap = manifest.buildMeasureNameToModelAndMeasureMap()
        val pair = measureMap[inputMeasure.name]
        if (pair == null) {
            log.warn(
                "Measure {} not found in any semantic model; " +
                    "skipping replacement on cumulative metric. " +
                    "(This should also be caught by validations.)",
                inputMeasure.name,
            )
            return null
        }
        val (semanticModel, measure) = pair
        val resolution = mapper.getOrCreateMetricForMeasure(
            manifest = manifest,
            modelName = semanticModel.name,
            measure = measure,
            fillNullsWith = inputMeasure.fillNullsWith,
            joinToTimespine = inputMeasure.joinToTimespine,
            existingMetricNames = existingNames,
            // Filters from the legacy input live on the complex metric's MetricInput, not on the
            // synthesized simple metric.
            measureInputFilters = null,
        )
        return MetricInputBuild(
            metricInput = MetricInput(
                name = resolution.metricName,
                filter = inputMeasure.filter,
                alias = inputMeasure.alias,
            ),
            manifest = resolution.manifest,
        )
    }
}

private fun SemanticManifest.replaceMetric(name: String, transform: (Metric) -> Metric): SemanticManifest {
    val newMetrics = metrics.map { if (it.name == name) transform(it) else it }
    return copy(metrics = newMetrics)
}
