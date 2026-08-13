package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import cc.monomer.metricflow.domain.manifest.validation.ValidationWarning

internal const val TEMP_CUSTOM_GRAIN_MSG = "Custom granularities are not supported for this field yet."

/**
 * Validates `CUMULATIVE` metrics: at most one of `measure` / `cumulative_type_params.metric`
 * is permitted, the granularities in `window` / `grain_to_date` must be valid, and `window`
 * + `grain_to_date` are mutually exclusive.
 *
 * Port of `metricflow_semantic_interfaces/validations/metrics.py::CumulativeMetricRule`.
 */
object CumulativeMetricRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()

        val customGranularityNames = semanticManifest.projectConfiguration.timeSpines
            .flatMap { it.customGranularities }
            .map { it.name }
            .toSet()
        val standardGranularities = TimeGranularity.entries.map { it.value.lowercase() }.toSet()

        for (metric in semanticManifest.metrics) {
            if (metric.type != MetricType.CUMULATIVE) continue

            issues.addAll(validateInputMeasureXorMetric(metric))

            val metricCtx = MetricContext(
                fileContext = FileContext.fromMetadata(metric.metadata),
                metric = MetricModelReference(metricName = metric.name),
            )

            // window field mismatch check (legacy vs new placement)
            val windowFieldOnTypeParams = metric.typeParams.window
            val windowFieldOnCum = metric.typeParams.cumulativeTypeParams?.window
            if (windowFieldOnTypeParams != null && windowFieldOnCum != null && windowFieldOnTypeParams != windowFieldOnCum) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Got differing values for `window` on cumulative metric '${metric.name}'. In " +
                            "`type_params.window`, got '$windowFieldOnTypeParams'. In " +
                            "`type_params.cumulative_type_params.window`, got " +
                            "'$windowFieldOnCum'. Please remove the value from " +
                            "`type_params.window`.",
                    ),
                )
            }

            val window = windowFieldOnCum ?: windowFieldOnTypeParams
            val grainToDate = metric.typeParams.cumulativeTypeParams?.grainToDate
                ?: metric.typeParams.grainToDate?.value

            if (grainToDate != null && grainToDate !in standardGranularities) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Invalid time granularity found in `grain_to_date`: '$grainToDate'. " +
                            TEMP_CUSTOM_GRAIN_MSG,
                    ),
                )
            }
            if (window != null && grainToDate != null) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Both window and grain_to_date set for cumulative metric. Please set one or the other.",
                    ),
                )
            }
            if (window != null) {
                issues.addAll(validateMetricTimeWindow(metricCtx, window, customGranularityNames, allowCustom = false))
            }
        }
        return issues
    }

    private fun validateInputMeasureXorMetric(metric: Metric): List<ValidationIssue> {
        val inputMetric = metric.typeParams.cumulativeTypeParams?.metric
        val measure = metric.typeParams.measure
        val ctx = MetricContext(
            fileContext = FileContext.fromMetadata(metric.metadata),
            metric = MetricModelReference(metricName = metric.name),
        )
        return when {
            measure != null && inputMetric != null -> listOf(
                ValidationWarning(
                    context = ctx,
                    message = "Cumulative metric '${metric.name}' should not have both a measure and a metric as " +
                        "inputs. The measure will be ignored; please remove it to avoid confusion.",
                ),
            )
            measure == null && inputMetric == null -> listOf(
                ValidationWarning(
                    context = ctx,
                    message = "Cumulative metric '${metric.name}' must have either a measure or a metric as inputs. " +
                        "Please add one of them.",
                ),
            )
            else -> emptyList()
        }
    }

    /**
     * Validate a metric time window's granularity string. Mirrors Python's
     * `CumulativeMetricRule.validate_metric_time_window`. Exposed `internal` so
     * `DerivedMetricRule` / `ConversionMetricRule` can reuse it.
     */
    internal fun validateMetricTimeWindow(
        metricContext: MetricContext,
        window: MetricTimeWindow,
        customGranularities: Set<String>,
        allowCustom: Boolean,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val standardGranularities = TimeGranularity.entries.map { it.value.lowercase() }.toSet()
        val validGranularities = customGranularities + standardGranularities
        var windowGranularity = window.granularity
        if (windowGranularity.endsWith("s") && windowGranularity.dropLast(1) in validGranularities) {
            windowGranularity = windowGranularity.dropLast(1)
        }
        val msg = "Invalid time granularity '$windowGranularity' in window: '${window.windowString}'"
        when {
            windowGranularity !in validGranularities -> issues.add(
                ValidationError(context = metricContext, message = msg),
            )
            !allowCustom && windowGranularity !in standardGranularities -> issues.add(
                ValidationError(context = metricContext, message = "$msg $TEMP_CUSTOM_GRAIN_MSG"),
            )
        }
        return issues
    }
}
