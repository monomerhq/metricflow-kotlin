package cc.monomer.metricflow.domain.manifest.validation.rules

import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MetricModelReference
import cc.monomer.metricflow.domain.manifest.validation.FileContext
import cc.monomer.metricflow.domain.manifest.validation.MetricContext
import cc.monomer.metricflow.domain.manifest.validation.SemanticManifestValidationRule
import cc.monomer.metricflow.domain.manifest.validation.ValidationError
import cc.monomer.metricflow.domain.manifest.validation.ValidationIssue
import cc.monomer.metricflow.domain.manifest.validation.ValidationWarning

/**
 * Checks aliases on constrained measure references are configured correctly: when a metric
 * uses the same measure multiple times with different filters, every constrained variant must
 * have a unique alias; aliases must not collide with measure names or with each other.
 *
 * Port of `metricflow_semantic_interfaces/validations/measures.py::MeasureConstraintAliasesRule`.
 */
object MeasureConstraintAliasesRule : SemanticManifestValidationRule {
    override fun validateManifest(semanticManifest: SemanticManifest): List<ValidationIssue> {
        val issues = mutableListOf<ValidationIssue>()
        val measureNames = semanticManifest.semanticModels
            .flatMap { it.measures }
            .map { it.reference.elementName }
            .toSet()
        val aliasToMetrics = mutableMapOf<String, MutableList<String>>()

        for (metric in semanticManifest.metrics) {
            val metricCtx = MetricContext(
                fileContext = FileContext.fromMetadata(metric.metadata),
                metric = MetricModelReference(metricName = metric.name),
            )
            issues.addAll(validateRequiredAliasesAreSet(metric, metricCtx))

            val aliasedMeasures = metric.inputMeasures.filter { it.alias != null }
            for (im in aliasedMeasures) {
                val alias = im.alias!!
                issues.addAll(UniqueAndValidNameRule.checkValidName(alias, metricCtx))
                if (alias in measureNames) {
                    issues.add(
                        ValidationError(
                            context = metricCtx,
                            message = "Alias `$alias` for measure `${im.name}` conflicts with measure names " +
                                "defined elsewhere in the model! This can cause ambiguity for certain types of " +
                                "query. Please choose another alias.",
                        ),
                    )
                }
                if (alias in aliasToMetrics) {
                    issues.add(
                        ValidationError(
                            context = metricCtx,
                            message = "Measure alias $alias conflicts with a measure alias used elsewhere in the " +
                                "model! This can cause ambiguity for certain types of query. Please choose another " +
                                "alias, or, if the measures are constrained in the same way, consider centralizing " +
                                "that definition in a new semantic model. Measure specification: $im. Existing " +
                                "metrics with that measure alias used: ${aliasToMetrics[alias]}",
                        ),
                    )
                }
                aliasToMetrics.getOrPut(alias) { mutableListOf() }.add(metric.name)
            }
        }
        return issues
    }

    private fun validateRequiredAliasesAreSet(
        metric: Metric,
        metricContext: MetricContext,
    ): List<ValidationIssue> {
        val measureRefs = metric.measureReferences
        if (measureRefs.size == measureRefs.toSet().size) return emptyList()

        val issues = mutableListOf<ValidationIssue>()
        val byName = metric.inputMeasures.groupBy { it.name }
        for ((name, inputMeasures) in byName) {
            if (inputMeasures.size == 1) continue

            val distinct = inputMeasures.toSet()
            if (distinct.size == 1) {
                issues.add(
                    ValidationWarning(
                        context = metricContext,
                        message = "PydanticMetric ${metric.name} has multiple identical input measures specifications for " +
                            "measure $name. This might be hiding a semantic error. Input measure specification: " +
                            "${inputMeasures[0]}.",
                    ),
                )
                continue
            }
            val constrainedWithoutAliases = inputMeasures.filter { it.filter != null && it.alias == null }
            if (constrainedWithoutAliases.isNotEmpty()) {
                issues.add(
                    ValidationError(
                        context = metricContext,
                        message = "PydanticMetric ${metric.name} depends on multiple different constrained versions of " +
                            "measure $name. In such cases, aliases must be provided, but the following input " +
                            "measures have constraints specified without an alias: " +
                            "$constrainedWithoutAliases.",
                    ),
                )
            }
        }
        return issues
    }
}
