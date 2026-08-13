package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.element.MeasureAggregationParameters
import cc.monomer.metricflow.domain.manifest.model.element.NonAdditiveDimensionParameters
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelElementReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference

/**
 * Helper functions shared between [cc.monomer.metricflow.domain.manifest.validation.rules.MeasureConstraintAliasesRule]
 * and related metric-side rules. Because simple metrics replaced legacy measures in metricflow,
 * many of the per-measure checks need to be re-runnable against per-metric inputs.
 *
 * Port of `metricflow_semantic_interfaces/validations/shared_measure_and_metric_helpers.py::SharedMeasureAndMetricHelpers`.
 */
internal object SharedMeasureAndMetricHelpers {

    /**
     * Validate a non-additive dimension declaration on a measure or metric.
     *
     * Mirrors Python's `validate_non_additive_dimension`. The Python `object_type_for_errors`
     * literal selects which context the messages reference — a quirk in Python where "Measure"
     * passes a [MetricContext] and "Metric" passes a [SemanticModelElementContext]. We mirror
     * the same flip exactly because the corpus oracle expects those literal context strings.
     */
    fun validateNonAdditiveDimension(
        objectName: String,
        semanticModel: SemanticModel,
        nonAdditiveDimension: NonAdditiveDimensionParameters,
        aggTimeDimensionReference: TimeDimensionReference,
        objectTypeForErrors: String,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val aggTimeDimension = semanticModel.dimensions
            .firstOrNull { it.name == aggTimeDimensionReference.elementName }

        fun ctx(): ValidationContext = when (objectTypeForErrors) {
            "Metric" -> SemanticModelElementContext(
                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                semanticModelElement = SemanticModelElementReference(
                    semanticModelName = semanticModel.name,
                    elementName = objectName,
                ),
                elementType = SemanticModelElementType.MEASURE,
            )
            "Measure" -> MetricContext(
                fileContext = FileContext.fromMetadata(semanticModel.metadata),
                metric = MetricModelReference(metricName = objectName),
            )
            else -> error("Unknown object_type_for_errors: $objectTypeForErrors")
        }

        if (aggTimeDimension == null) {
            issues.add(
                ValidationError(
                    context = ctx(),
                    message = "$objectTypeForErrors '$objectName' has a agg_time_dimension of " +
                        "${aggTimeDimensionReference.elementName} " +
                        "that is not defined as a dimension in semantic model '${semanticModel.name}'.",
                ),
            )
            return issues
        }

        val matchingDimension = semanticModel.dimensions
            .firstOrNull { it.name == nonAdditiveDimension.name }
        if (matchingDimension == null) {
            issues.add(
                ValidationError(
                    context = ctx(),
                    message = "$objectTypeForErrors '$objectName' has a non_additive_dimension with name " +
                        "'${nonAdditiveDimension.name}' that is not defined as a dimension in semantic " +
                        "model '${semanticModel.name}'.",
                ),
            )
        } else {
            if (matchingDimension.type != DimensionType.TIME) {
                issues.add(
                    ValidationError(
                        context = ctx(),
                        message = "$objectTypeForErrors '$objectName' has a non_additive_dimension with name" +
                            "'${nonAdditiveDimension.name}' " +
                            "that is defined as a categorical dimension which is not supported.",
                    ),
                )
            }
            val mTp = matchingDimension.typeParams
            val aTp = aggTimeDimension.typeParams
            if (mTp != null && aTp != null && mTp.timeGranularity != aTp.timeGranularity) {
                issues.add(
                    ValidationError(
                        context = ctx(),
                        message = "$objectTypeForErrors '$objectName' has a non_additive_dimension with name " +
                            "'${nonAdditiveDimension.name}' that has a base time granularity " +
                            "(${mTp.timeGranularity.name}) that is not equal to " +
                            "the ${objectTypeForErrors.lowercase()}'s agg_time_dimension ${aggTimeDimension.name} " +
                            "with a base granularity of (${aTp.timeGranularity.name}).",
                    ),
                )
            }
        }

        if (nonAdditiveDimension.windowChoice !in setOf(AggregationType.MIN, AggregationType.MAX)) {
            issues.add(
                ValidationError(
                    context = ctx(),
                    message = "$objectTypeForErrors '$objectName' has a non_additive_dimension with an invalid " +
                        "'window_choice' of '${nonAdditiveDimension.windowChoice.value}'. " +
                        "Only choices supported are 'min' or 'max'.",
                ),
            )
        }

        val entitiesInSemanticModel = semanticModel.entities.map { it.name }.toSet()
        val windowGroupings = nonAdditiveDimension.windowGroupings.toSet()
        val intersected = windowGroupings.intersect(entitiesInSemanticModel)
        if (intersected.size != windowGroupings.size) {
            val missing = windowGroupings - intersected
            issues.add(
                ValidationError(
                    context = ctx(),
                    message = "$objectTypeForErrors '$objectName' has a non_additive_dimension with an invalid " +
                        "'window_groupings'. These entities $missing do not exist in the semantic model.",
                ),
            )
        }
        return issues
    }

    /**
     * Validate the `expr` requirement for COUNT aggregations.
     *
     * Mirrors Python's `validate_expr_for_count_aggregation`.
     */
    fun validateExprForCountAggregation(
        context: ValidationContext,
        objectName: String,
        objectType: String,
        aggType: AggregationType,
        expr: String?,
    ): List<ValidationIssue> {
        if (aggType != AggregationType.COUNT) return emptyList()
        val issues = mutableListOf<ValidationIssue>()
        if (expr == null) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "$objectType '$objectName' uses a COUNT aggregation, which requires an expr to be " +
                        "provided. Provide 'expr: 1' if a count of all rows is desired.",
                ),
            )
        }
        if (expr != null && expr.lowercase().startsWith("distinct ")) {
            issues.add(
                ValidationError(
                    context = context,
                    message = "$objectType '$objectName' uses a '${aggType.value}' aggregation with a DISTINCT " +
                        "expr: '$expr'. This is not supported as it effectively converts an additive " +
                        "${objectType.lowercase()} into a non-additive one, and this could cause certain queries to " +
                        "return incorrect results. Please use the ${aggType.value}_distinct aggregation type.",
                ),
            )
        }
        return issues
    }

    /**
     * Validate PERCENTILE / MEDIAN aggregation parameter consistency.
     *
     * Mirrors Python's `validate_percentile_arguments`.
     */
    fun validatePercentileArguments(
        context: ValidationContext,
        objectName: String,
        objectType: String,
        aggType: AggregationType?,
        aggParams: MeasureAggregationParameters?,
    ): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        when (aggType) {
            AggregationType.PERCENTILE -> {
                val pct = aggParams?.percentile
                if (pct == null) {
                    issues.add(
                        ValidationError(
                            context = context,
                            message = "$objectType '$objectName' uses a PERCENTILE aggregation, which requires " +
                                "agg_params.percentile to be provided.",
                        ),
                    )
                } else if (pct <= 0.0 || pct >= 1.0) {
                    issues.add(
                        ValidationError(
                            context = context,
                            message = "Percentile aggregation parameter for ${objectType.lowercase()} '$objectName' is " +
                                "'$pct', but must be between 0 and 1 (non-inclusive). " +
                                "For example, to indicate the 65th percentile value, set 'percentile: 0.65'. " +
                                "For percentile values of 0, please use MIN, for percentile values of 1, please use MAX.",
                        ),
                    )
                }
            }
            AggregationType.MEDIAN -> {
                if (aggParams != null) {
                    if (aggParams.percentile != null && aggParams.percentile != 0.5) {
                        issues.add(
                            ValidationError(
                                context = context,
                                message = "$objectType '$objectName' uses a MEDIAN aggregation, while percentile is " +
                                    "set to '${aggParams.percentile}', a conflicting value. Please remove " +
                                    "the parameter or set to '0.5'.",
                            ),
                        )
                    }
                    if (aggParams.useDiscretePercentile) {
                        issues.add(
                            ValidationError(
                                context = context,
                                message = "$objectType '$objectName' uses a MEDIAN aggregation, while " +
                                    "use_discrete_percentile is set to true. Please remove the parameter or set " +
                                    "to False.",
                            ),
                        )
                    }
                }
            }
            else -> {
                if (aggParams != null && (
                        (aggParams.percentile != null && aggParams.percentile != 0.0) ||
                            aggParams.useDiscretePercentile ||
                            aggParams.useApproximatePercentile
                        )
                ) {
                    val wrongParams = buildList {
                        if (aggParams.percentile != null && aggParams.percentile != 0.0) add("percentile")
                        if (aggParams.useDiscretePercentile) add("use_discrete_percentile")
                        if (aggParams.useApproximatePercentile) add("use_approximate_percentile")
                    }
                    val wrongParamsStr = wrongParams.joinToString(", ")
                    val aggTypeStr = aggType?.value ?: "None"
                    issues.add(
                        ValidationError(
                            context = context,
                            message = "$objectType '$objectName' with aggregation '$aggTypeStr' uses agg_params " +
                                "($wrongParamsStr) only relevant to Percentile ${objectType.lowercase()}s.",
                        ),
                    )
                }
            }
        }
        return issues
    }
}
