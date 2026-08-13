package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.validation.rules.AggregationTimeDimensionRule
import cc.monomer.metricflow.domain.manifest.validation.rules.ConversionMetricRule
import cc.monomer.metricflow.domain.manifest.validation.rules.CountAggregationExprRule
import cc.monomer.metricflow.domain.manifest.validation.rules.CumulativeMetricRule
import cc.monomer.metricflow.domain.manifest.validation.rules.DerivedMetricRule
import cc.monomer.metricflow.domain.manifest.validation.rules.DimensionConsistencyRule
import cc.monomer.metricflow.domain.manifest.validation.rules.ElementConsistencyRule
import cc.monomer.metricflow.domain.manifest.validation.rules.EntityLabelsRule
import cc.monomer.metricflow.domain.manifest.validation.rules.MeasureConstraintAliasesRule
import cc.monomer.metricflow.domain.manifest.validation.rules.MeasuresNonAdditiveDimensionRule
import cc.monomer.metricflow.domain.manifest.validation.rules.MetricLabelsRule
import cc.monomer.metricflow.domain.manifest.validation.rules.MetricMeasuresRule
import cc.monomer.metricflow.domain.manifest.validation.rules.NaturalEntityConfigurationRule
import cc.monomer.metricflow.domain.manifest.validation.rules.NonEmptyRule
import cc.monomer.metricflow.domain.manifest.validation.rules.PercentileAggregationRule
import cc.monomer.metricflow.domain.manifest.validation.rules.PrimaryEntityDimensionPairs
import cc.monomer.metricflow.domain.manifest.validation.rules.PrimaryEntityRule
import cc.monomer.metricflow.domain.manifest.validation.rules.ReservedKeywordsRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SavedQueryRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SemanticModelDefaultsRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SemanticModelLabelsRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SemanticModelMeasuresUniqueRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SemanticModelValidityWindowRule
import cc.monomer.metricflow.domain.manifest.validation.rules.SimpleMetricExprRule
import cc.monomer.metricflow.domain.manifest.validation.rules.TimeDimensionHasGranularityRule
import cc.monomer.metricflow.domain.manifest.validation.rules.TimeSpineRule
import cc.monomer.metricflow.domain.manifest.validation.rules.UniqueAndValidNameRule
import cc.monomer.metricflow.domain.manifest.validation.rules.WhereFiltersAreParseable

/**
 * The canonical 28-rule pipeline that [SemanticManifestValidator.withDefaultRules] runs.
 *
 * Port of `metricflow_semantic_interfaces/validations/semantic_manifest_validator.py::SemanticManifestValidator.DEFAULT_RULES`.
 *
 * **Order matters** — it mirrors Python's `DEFAULT_RULES` tuple verbatim, so issues come out in
 * the same sequence Python would produce. Issue ordering is the load-bearing comparison axis
 * for the parity test.
 *
 * Not in this list (intentionally skipped, mirroring Python): `CommonEntitysRule` from
 * `common_entities.py`. metricflow ships it defined but absent from `DEFAULT_RULES` because
 * it's noisy in real dbt projects. See the module README.
 */
val DefaultValidationRules: List<SemanticManifestValidationRule> = listOf(
    PercentileAggregationRule,
    DerivedMetricRule,
    CountAggregationExprRule,
    SemanticModelMeasuresUniqueRule,
    SemanticModelValidityWindowRule,
    DimensionConsistencyRule,
    ElementConsistencyRule,
    NaturalEntityConfigurationRule,
    MeasureConstraintAliasesRule,
    MetricMeasuresRule,
    CumulativeMetricRule,
    NonEmptyRule,
    UniqueAndValidNameRule,
    AggregationTimeDimensionRule,
    ReservedKeywordsRule,
    MeasuresNonAdditiveDimensionRule,
    SemanticModelDefaultsRule,
    PrimaryEntityRule,
    PrimaryEntityDimensionPairs,
    WhereFiltersAreParseable,
    SavedQueryRule,
    MetricLabelsRule,
    SemanticModelLabelsRule,
    EntityLabelsRule,
    ConversionMetricRule,
    TimeSpineRule,
    TimeDimensionHasGranularityRule,
    SimpleMetricExprRule,
)
