package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
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
 * For legacy SIMPLE metrics that reference a measure via `type_params.measure`, asserts that
 * the measure exists and (if `expr` is set) that the expr matches the measure's expr or name.
 *
 * Port of `metricflow_semantic_interfaces/validations/metrics.py::SimpleMetricExprRule`.
 */
object SimpleMetricExprRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val measures = semanticManifest.semanticModels
            .flatMap { it.measures }
            .associateBy { it.reference }

        for (metric in semanticManifest.metrics) {
            if (metric.type != MetricType.SIMPLE) continue
            val measureSpec = metric.typeParams.measure ?: continue
            val ref = MeasureReference(measureSpec.name)
            val referenced = measures[ref]
            if (referenced == null) {
                issues.add(
                    ValidationError(
                        context = MetricContext(
                            fileContext = FileContext.fromMetadata(metric.metadata),
                            metric = MetricModelReference(metricName = metric.name),
                        ),
                        message = "Measure '${ref.elementName}'" +
                            "not found in semantic manifest",
                    ),
                )
                continue
            }
            val metricExpr = metric.typeParams.expr
            if (metricExpr != null && metricExpr != referenced.expr && metricExpr != referenced.name) {
                issues.add(
                    ValidationWarning(
                        context = MetricContext(
                            fileContext = FileContext.fromMetadata(metric.metadata),
                            metric = MetricModelReference(metricName = metric.name),
                        ),
                        message = "Metric '${metric.name}' should not have an expr set if it's proxy from measures",
                    ),
                )
            }
        }
        return issues
    }
}
