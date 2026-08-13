package cc.monomer.metricflow.domain.manifest.transformation

import cc.monomer.metricflow.domain.manifest.transformation.rules.AddInputMetricMeasuresRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.BooleanAggregationRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.BooleanMeasureAggregationRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertCountMetricToSumRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertCountToSumRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertMedianMetricToPercentile
import cc.monomer.metricflow.domain.manifest.transformation.rules.ConvertMedianToPercentileRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.CreateProxyMeasureRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.FixProxyMetricsRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.FlattenSimpleMetricsWithMeasureInputsRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.LowerCaseNamesRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.RemovePluralFromWindowGranularityRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.ReplaceInputMeasuresWithSimpleMetricsTransformationRule
import cc.monomer.metricflow.domain.manifest.transformation.rules.SetCumulativeTypeParamsRule

/**
 * The canonical 14-rule transformation pipeline used to canonicalise a parsed manifest into the
 * shape downstream validation and query-planning code expects.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/pydantic_rule_set.py::PydanticSemanticManifestTransformRuleSet`.
 *
 * Composition (order matters):
 *
 * **Primary phase** — top-level normalisation:
 *  1. [LowerCaseNamesRule]
 *
 * **Secondary phase**, made up of three sub-blocks (in order):
 *
 * *legacy measure update rules* — primarily editing legacy measures.
 *  2. [BooleanMeasureAggregationRule]
 *  3. [ConvertCountToSumRule]
 *  4. [ConvertMedianToPercentileRule]
 *
 * *convert legacy measures to metrics rules* — create or update metrics to replace measures.
 *  5. [CreateProxyMeasureRule]  (must be FIRST — later rules depend on the proxy metrics)
 *  6. [AddInputMetricMeasuresRule]
 *  7. [FlattenSimpleMetricsWithMeasureInputsRule]
 *  8. [ReplaceInputMeasuresWithSimpleMetricsTransformationRule]
 *  9. [FixProxyMetricsRule]
 *
 * *general metric update rules* — apply universally to any metric meeting their criteria.
 *  10. [SetCumulativeTypeParamsRule]
 *  11. [RemovePluralFromWindowGranularityRule]
 *  12. [ConvertMedianMetricToPercentile]
 *  13. [ConvertCountMetricToSumRule]
 *  14. [BooleanAggregationRule]
 */
val DefaultTransformRuleSet: SemanticManifestTransformRuleSet = SemanticManifestTransformRuleSet(
    primaryRules = listOf(LowerCaseNamesRule),
    secondaryRules = listOf(
        // legacy_measure_update_rules
        BooleanMeasureAggregationRule,
        ConvertCountToSumRule,
        ConvertMedianToPercentileRule,
        // convert_legacy_measures_to_metrics_rules
        CreateProxyMeasureRule, // FIRST in this sequence, per Python comment.
        AddInputMetricMeasuresRule,
        FlattenSimpleMetricsWithMeasureInputsRule,
        ReplaceInputMeasuresWithSimpleMetricsTransformationRule,
        FixProxyMetricsRule,
        // general_metric_update_rules
        SetCumulativeTypeParamsRule,
        RemovePluralFromWindowGranularityRule,
        ConvertMedianMetricToPercentile,
        ConvertCountMetricToSumRule,
        BooleanAggregationRule,
    ),
)
