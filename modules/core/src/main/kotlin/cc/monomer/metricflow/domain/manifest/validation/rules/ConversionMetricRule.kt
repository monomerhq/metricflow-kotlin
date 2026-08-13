package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.ConversionTypeParams
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.MetricInputMeasure
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import cc.monomer.metricflow.domain.manifest.validation.ValidationWarning

/**
 * Validates `CONVERSION` metrics: well-formed `base_measure` xor `base_metric`, valid entity,
 * windows that fit the granularity set, constant properties resolving to real model elements.
 *
 * Port of `metricflow_semantic_interfaces/validations/metrics.py::ConversionMetricRule`.
 *
 * The Python version checks both `_validate_measure_xor_metric_for_each_input` (per-side
 * presence) AND `_get_validated_model_for_input` (per-side semantic-model resolution). Each
 * fires its own warning when both inputs are present, so a metric with `base_measure +
 * base_metric + conversion_measure + conversion_metric` produces FOUR warnings — matching the
 * corpus for `visit_buy_conversion_rate_with_monthly_conversion`.
 */
object ConversionMetricRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val customGranularityNames = semanticManifest.projectConfiguration.timeSpines
            .flatMap { it.customGranularities }
            .map { it.name }
            .toSet()

        for (metric in semanticManifest.metrics) {
            if (metric.type != MetricType.CONVERSION) continue
            val ctp = metric.typeParams.conversionTypeParams ?: continue

            issues.addAll(validateMeasureXorMetricForEachInput(metric, ctp))

            val (baseModel, baseIssues) = getValidatedModelForInput(
                inputMeasure = ctp.baseMeasure,
                inputMetric = ctp.baseMetric,
                metric = metric,
                inputType = "base",
                semanticManifest = semanticManifest,
            )
            issues.addAll(baseIssues)

            val (conversionModel, conversionIssues) = getValidatedModelForInput(
                inputMeasure = ctp.conversionMeasure,
                inputMetric = ctp.conversionMetric,
                metric = metric,
                inputType = "conversion",
                semanticManifest = semanticManifest,
            )
            issues.addAll(conversionIssues)

            if (baseModel == null || conversionModel == null) continue

            issues.addAll(validateEntityExists(metric, ctp.entity, baseModel, conversionModel))
            issues.addAll(validateMeasures(metric, ctp, baseModel, conversionModel))
            issues.addAll(validateMetrics(metric, ctp, semanticManifest))
            issues.addAll(validateTypeParams(metric, ctp, customGranularityNames))
            issues.addAll(validateConstantProperties(metric, ctp, baseModel, conversionModel))
        }
        return issues
    }

    private fun validateMeasureXorMetricForEachInput(
        metric: Metric,
        ctp: ConversionTypeParams,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val ctx = metricContext(metric)
        if (ctp.baseMeasure != null && ctp.baseMetric != null) {
            issues.add(
                ValidationWarning(
                    context = ctx,
                    message = "Conversion metric '${metric.name}' should not have both a base measure " +
                        "and a base metric as inputs. The base measure will be ignored; please " +
                        "remove it to avoid confusion.",
                ),
            )
        } else if (ctp.baseMeasure == null && ctp.baseMetric == null) {
            issues.add(
                ValidationError(
                    context = ctx,
                    message = "Conversion metric '${metric.name}' must have either a base measure or a base metric " +
                        "as inputs. Please add one of them.",
                ),
            )
        }
        if (ctp.conversionMeasure != null && ctp.conversionMetric != null) {
            issues.add(
                ValidationWarning(
                    context = ctx,
                    message = "Conversion metric '${metric.name}' should not have both a conversion measure " +
                        "and a conversion metric as inputs. The conversion measure will be ignored; please " +
                        "remove it to avoid confusion.",
                ),
            )
        } else if (ctp.conversionMeasure == null && ctp.conversionMetric == null) {
            // Python literally has a `{metric.name}` placeholder bug here — it didn't use f-string.
            // Mirror exactly so the corpus parity test passes.
            issues.add(
                ValidationError(
                    context = ctx,
                    message = "Conversion metric '{metric.name}' must have either a conversion measure or " +
                        "a conversion metric as inputs. Please add one of them.",
                ),
            )
        }
        return issues
    }

    private fun getValidatedModelForInput(
        inputMeasure: MetricInputMeasure?,
        inputMetric: MetricInput?,
        metric: Metric,
        inputType: String,
        semanticManifest: SemanticManifest,
    ): Pair<SemanticModel?, List<ValidationIssue>> {
        val issues = mutableListOf<ValidationIssue>()
        val ctx = metricContext(metric)

        if (inputMetric != null) {
            val realInputMetric = semanticManifest.metrics.firstOrNull { it.name == inputMetric.name }
            if (realInputMetric != null && realInputMetric.type != MetricType.SIMPLE) {
                issues.add(
                    ValidationError(
                        context = MetricContext(
                            fileContext = FileContext.fromMetadata(realInputMetric.metadata),
                            metric = MetricModelReference(metricName = realInputMetric.name),
                        ),
                        message = "Metric '${realInputMetric.name}' is not a Simple metric, so it cannot " +
                            "be used as an input for Conversion metric '${metric.name}'.",
                    ),
                )
            }
        }

        var model: SemanticModel? = null

        when {
            inputMeasure != null && inputMetric != null -> {
                issues.add(
                    ValidationWarning(
                        context = ctx,
                        message = "Conversion metric '${metric.name}' should not have both a $inputType measure " +
                            "and a $inputType metric as inputs. The measure input will be ignored; please " +
                            "remove it to avoid confusion.",
                    ),
                )
            }
            inputMeasure == null && inputMetric == null -> {
                issues.add(
                    ValidationError(
                        context = ctx,
                        message = "Conversion metric '${metric.name}' must have either a $inputType measure " +
                            "or a $inputType metric as an input. Please add one of them.",
                    ),
                )
            }
            inputMeasure != null -> {
                model = getSemanticModelFromMeasure(inputMeasure.measureReference, semanticManifest)
                if (model == null) {
                    issues.add(
                        ValidationError(
                            context = ctx,
                            message = "Input measure '${inputMeasure.measureReference.elementName}' for conversion metric " +
                                "'${metric.name}' does not exist in your manifest.",
                        ),
                    )
                }
            }
            inputMetric != null -> {
                model = getSemanticModelPointedToByMetric(inputMetric.name, semanticManifest)
                if (model == null) {
                    issues.add(
                        ValidationError(
                            context = ctx,
                            message = "Input metric '${inputMetric.name}' for conversion metric " +
                                "'${metric.name}' is linked to a semantic model that does " +
                                "not exist in your manifest.",
                        ),
                    )
                }
            }
        }
        return model to issues
    }

    private fun getSemanticModelFromMeasure(
        measureReference: MeasureReference,
        manifest: SemanticManifest,
    ): SemanticModel? =
        manifest.semanticModels.firstOrNull { sm ->
            sm.measures.any { it.reference == measureReference }
        }

    private fun getSemanticModelPointedToByMetric(metricName: String, manifest: SemanticManifest): SemanticModel? {
        val metric = manifest.metrics.firstOrNull { it.name == metricName } ?: return null
        val agg = metric.typeParams.metricAggregationParams ?: return null
        return manifest.semanticModels.firstOrNull { it.name == agg.semanticModel }
    }

    private fun validateEntityExists(
        metric: Metric,
        entity: String,
        baseModel: SemanticModel,
        conversionModel: SemanticModel,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val ctx = metricContext(metric)
        if (baseModel.entities.none { it.name == entity }) {
            issues.add(
                ValidationError(
                    context = ctx,
                    message = "Entity: $entity not found in base semantic model: ${baseModel.name}.",
                ),
            )
        }
        if (conversionModel.entities.none { it.name == entity }) {
            issues.add(
                ValidationError(
                    context = ctx,
                    message = "Entity: $entity not found in conversion semantic model: ${conversionModel.name}.",
                ),
            )
        }
        return issues
    }

    private fun validateMeasures(
        metric: Metric,
        ctp: ConversionTypeParams,
        baseModel: SemanticModel,
        conversionModel: SemanticModel,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun validateMeasure(inputMeasure: MetricInputMeasure, model: SemanticModel, isBaseInput: Boolean) {
            val measure = model.measures.firstOrNull { it.reference == inputMeasure.measureReference } ?: return
            issues.addAll(validateAggAndExpr(measure.agg, measure.expr, measure.name, "Measure", metric))
            issues.addAll(validateNoFilterForConversionInput(inputMeasure.filter, measure.name, "Measure", isBaseInput, metric))
        }
        ctp.baseMeasure?.let { validateMeasure(it, baseModel, true) }
        ctp.conversionMeasure?.let { validateMeasure(it, conversionModel, false) }
        return issues
    }

    private fun validateMetrics(
        metric: Metric,
        ctp: ConversionTypeParams,
        manifest: SemanticManifest,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun validateOne(inputMetric: MetricInput, isBaseMetric: Boolean) {
            val resolved = manifest.metrics.firstOrNull { it.name == inputMetric.name } ?: return
            val aggParams = resolved.typeParams.metricAggregationParams ?: return
            issues.addAll(validateAggAndExpr(aggParams.agg, resolved.typeParams.expr, resolved.name, "Metric", resolved))
            issues.addAll(validateNoFilterForConversionInput(inputMetric.filter, resolved.name, "Metric", isBaseMetric, resolved))
        }
        ctp.baseMetric?.let { validateOne(it, true) }
        ctp.conversionMetric?.let { validateOne(it, false) }
        return issues
    }

    private fun validateAggAndExpr(
        aggType: AggregationType,
        expr: String?,
        inputName: String,
        inputObjectType: String,
        mainMetric: Metric,
    ): List<ValidationIssue> {
        if (aggType == AggregationType.COUNT || aggType == AggregationType.COUNT_DISTINCT ||
            (aggType == AggregationType.SUM && expr == "1")
        ) {
            return emptyList()
        }
        return listOf(
            ValidationError(
                context = metricContext(mainMetric),
                message = "For conversion metrics, the input ${inputObjectType.lowercase()} must be " +
                    "COUNT/SUM(1)/COUNT_DISTINCT. $inputObjectType '$inputName' is agg type: $aggType",
            ),
        )
    }

    private fun validateNoFilterForConversionInput(
        filter: Any?,
        inputName: String,
        inputObjectType: String,
        isBaseInput: Boolean,
        mainMetric: Metric,
    ): List<ValidationIssue> {
        if (filter == null || isBaseInput) return emptyList()
        return listOf(
            ValidationWarning(
                context = metricContext(mainMetric),
                message = "$inputObjectType input '$inputName' has a filter. " +
                    "For conversion metrics, filtering on the conversion " +
                    "input is not fully supported yet. ",
            ),
        )
    }

    private fun validateTypeParams(
        metric: Metric,
        ctp: ConversionTypeParams,
        customGranularityNames: Set<String>,
    ): List<ValidationIssue> {
        val window = ctp.window ?: return emptyList()
        return CumulativeMetricRule.validateMetricTimeWindow(
            metricContext = metricContext(metric),
            window = window,
            customGranularities = customGranularityNames,
            allowCustom = false,
        )
    }

    private fun validateConstantProperties(
        metric: Metric,
        ctp: ConversionTypeParams,
        baseModel: SemanticModel,
        conversionModel: SemanticModel,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        fun elementsInModel(references: List<String>, model: SemanticModel) {
            val linkable = model.entities.map { it.name } + model.dimensions.map { it.name }
            for (ref in references) {
                if (ref !in linkable) {
                    issues.add(
                        ValidationError(
                            context = metricContext(metric),
                            message = "The provided constant property: $ref, " +
                                "cannot be found in semantic model ${model.name}",
                        ),
                    )
                }
            }
        }
        val constantProperties = ctp.constantProperties ?: return emptyList()
        elementsInModel(constantProperties.map { it.baseProperty }, baseModel)
        elementsInModel(constantProperties.map { it.conversionProperty }, conversionModel)
        return issues
    }

    private fun metricContext(metric: Metric): MetricContext = MetricContext(
        fileContext = FileContext.fromMetadata(metric.metadata),
        metric = MetricModelReference(metricName = metric.name),
    )
}
