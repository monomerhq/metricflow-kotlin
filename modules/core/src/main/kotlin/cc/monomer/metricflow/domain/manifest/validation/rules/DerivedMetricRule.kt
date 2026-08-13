package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
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

/**
 * Validates `DERIVED` metrics: the metric must list its input metrics, each input must exist,
 * aliases must not collide, offset windows / `offset_to_grain` must be valid and mutually
 * exclusive, and the `expr` must reference every input metric.
 *
 * Port of `metricflow_semantic_interfaces/validations/metrics.py::DerivedMetricRule`.
 */
object DerivedMetricRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val customGranularityNames = semanticManifest.projectConfiguration.timeSpines
            .flatMap { it.customGranularities }
            .map { it.name }
            .toSet()

        issues.addAll(validateInputMetricsExist(semanticManifest))
        for (metric in semanticManifest.metrics) {
            issues.addAll(validateAliasCollision(metric))
            issues.addAll(validateTimeOffsetParams(metric, customGranularityNames))
            issues.addAll(validateExpr(metric))
        }
        return issues
    }

    private fun validateAliasCollision(metric: Metric): List<ValidationIssue> {
        if (metric.type != MetricType.DERIVED) return emptyList()
        val issues = mutableListOf<ValidationIssue>()
        val metricCtx = MetricContext(
            fileContext = FileContext.fromMetadata(metric.metadata),
            metric = MetricModelReference(metricName = metric.name),
        )
        val inputMetrics = metric.typeParams.metrics ?: emptyList()
        val usedNames = inputMetrics.map { it.name }.toMutableSet()
        for (im in inputMetrics) {
            val alias = im.alias ?: continue
            issues.addAll(UniqueAndValidNameRule.checkValidName(alias, metricCtx))
            if (alias in usedNames) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Alias '$alias' for input metric: '${im.name}' is " +
                            "already being used. Please choose another alias.",
                    ),
                )
                usedNames.add(alias)
            }
        }
        return issues
    }

    private fun validateInputMetricsExist(manifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val allMetrics = manifest.metrics.map { it.name }.toSet()
        for (metric in manifest.metrics) {
            if (metric.type != MetricType.DERIVED) continue
            val metricCtx = MetricContext(
                fileContext = FileContext.fromMetadata(metric.metadata),
                metric = MetricModelReference(metricName = metric.name),
            )
            val inputs = metric.typeParams.metrics
            if (inputs.isNullOrEmpty()) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "No input metrics found for derived metric '${metric.name}'. " +
                            "Please add metrics to type_params.metrics.",
                    ),
                )
            }
            for (im in inputs ?: emptyList()) {
                if (im.name !in allMetrics) {
                    issues.add(
                        ValidationError(
                            context = metricCtx,
                            message = "For metric: ${metric.name}, input metric: '${im.name}' does not " +
                                "exist as a configured metric in the model.",
                        ),
                    )
                }
            }
        }
        return issues
    }

    private fun validateTimeOffsetParams(
        metric: Metric,
        customGranularities: Set<String>,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val standardGranularities = TimeGranularity.entries.map { it.value.lowercase() }.toSet()
        val metricCtx = MetricContext(
            fileContext = FileContext.fromMetadata(metric.metadata),
            metric = MetricModelReference(metricName = metric.name),
        )
        for (im in metric.typeParams.metrics ?: emptyList()) {
            val window = im.offsetWindow
            if (window != null) {
                issues.addAll(
                    CumulativeMetricRule.validateMetricTimeWindow(
                        metricContext = metricCtx,
                        window = window,
                        customGranularities = customGranularities,
                        allowCustom = true,
                    ),
                )
            }
            if (im.offsetToGrain != null && im.offsetToGrain !in standardGranularities) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Invalid time granularity found in `offset_to_grain`: '${im.offsetToGrain}'. " +
                            TEMP_CUSTOM_GRAIN_MSG,
                    ),
                )
            }
            if (im.offsetWindow != null && im.offsetToGrain != null) {
                issues.add(
                    ValidationError(
                        context = metricCtx,
                        message = "Both offset_window and offset_to_grain set for derived metric '${metric.name}' on " +
                            "input metric '${im.name}'. Please set one or the other.",
                    ),
                )
            }
        }
        return issues
    }

    private fun validateExpr(metric: Metric): List<ValidationIssue> {
        if (metric.type != MetricType.DERIVED) return emptyList()
        val issues = mutableListOf<ValidationIssue>()
        val ctx = MetricContext(
            fileContext = FileContext.fromMetadata(metric.metadata),
            metric = MetricModelReference(metricName = metric.name),
        )
        val expr = metric.typeParams.expr
        if (expr.isNullOrEmpty()) {
            issues.add(
                ValidationWarning(
                    context = ctx,
                    message = "No `expr` set for derived metric ${metric.name}. " +
                        "Please add an `expr` that references all input metrics.",
                ),
            )
        } else {
            for (im in metric.typeParams.metrics ?: emptyList()) {
                val name = im.alias ?: im.name
                if (!expr.contains(name)) {
                    issues.add(
                        ValidationWarning(
                            context = ctx,
                            message = "Input metric '$name' is not used in `expr`: '$expr' for " +
                                "derived metric '${metric.name}'. Please update the `expr` or remove the input metric.",
                        ),
                    )
                }
            }
        }
        return issues
    }
}
