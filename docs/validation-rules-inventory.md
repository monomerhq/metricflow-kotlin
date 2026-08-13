# Phase 0 — Validation rules inventory

Every rule that runs when `SemanticManifestValidator.checked_validations(manifest)` is called. Source: [`metricflow_semantic_interfaces/validations/semantic_manifest_validator.py:75-104`](../python_oracle/upstream/metricflow_semantic_interfaces/validations/semantic_manifest_validator.py).

## Headline

- **16 rule-bearing files** under `metricflow_semantic_interfaces/validations/` (excluding the disabled `common_entities.py`).
- **28 rule classes** in the active `DEFAULT_RULES` tuple — multiple rules per file is common (e.g. `metrics.py` defines 4 active rules, `measures.py` 6, `labels.py` 3, `unique_valid_name.py` 2, `semantic_models.py` 2).
- **1 file (`common_entities.py`) is unreachable** — `CommonEntitysRule` is defined but **not** in `DEFAULT_RULES`.
- All rules subclass `SemanticManifestValidationRule[SemanticManifestT]`. Each emits zero or more `ValidationIssue`s of severity `ERROR`, `FUTURE_ERROR`, or `WARNING`.

`FEASIBILITY.md` mentions "11 rules" — this is significantly off. The actual surface is **28 rules**. See "Proposed updates to FEASIBILITY.md" in the Phase 0 report.

## Rule severity convention

A rule's `validate_manifest` method returns a `Sequence[ValidationIssue]`. Each issue has a level:

- **`ERROR`** — `checked_validations` raises `SemanticManifestValidationException`.
- **`FUTURE_ERROR`** — currently a warning; will become an error in a later metricflow release.
- **`WARNING`** — informational; never raises.

A single rule can emit multiple severities depending on the condition it hits. The "Severities" column below is the set of levels actually emitted by the rule's source (counted via `grep -c "ValidationError"` etc.).

## Inventory (grouped by file)

| File | Rule class | What it checks | Severities |
|---|---|---|---|
| `agg_time_dimension.py` | `AggregationTimeDimensionRule` | The `agg_time_dimension` for a measure points to a valid time dimension in the same semantic model. | ERROR |
| `dimension_const.py` | `DimensionConsistencyRule` | Dimensions with the same name across semantic models have consistent type/granularity/expr. | ERROR |
| `element_const.py` | `ElementConsistencyRule` | Elements (dimensions, measures, entities) with the same name across semantic models share the element type. | ERROR |
| `entities.py` | `NaturalEntityConfigurationRule` | Entities marked `EntityType.NATURAL` are configured correctly (require an SCD validity-window setup). | ERROR |
| `labels.py` | `MetricLabelsRule` | Metric labels are unique. | ERROR |
| `labels.py` | `SemanticModelLabelsRule` | Semantic-model labels are unique. | ERROR |
| `labels.py` | `EntityLabelsRule` | Entity labels are consistent across semantic models. | ERROR |
| `measures.py` | `SemanticModelMeasuresUniqueRule` | All measure names are unique across the manifest. | ERROR |
| `measures.py` | `MeasureConstraintAliasesRule` | Aliases are configured correctly when constrained measure references are used. | ERROR + WARNING |
| `measures.py` | `MetricMeasuresRule` | Measures referenced from metrics actually exist. | ERROR |
| `measures.py` | `MeasuresNonAdditiveDimensionRule` | A measure's `non_additive_dimensions` are properly defined (the named dim exists, is a time dim, etc.). | ERROR + WARNING |
| `measures.py` | `CountAggregationExprRule` | `COUNT` measures have an `expr` provided. | ERROR |
| `measures.py` | `PercentileAggregationRule` | Only `PERCENTILE` measures may have `agg_params`; if so, the percentile value is in `(0, 1]`. | ERROR |
| `metrics.py` | `CumulativeMetricRule` | Cumulative metrics are configured properly (window, grain_to_date constraints). | ERROR + WARNING |
| `metrics.py` | `DerivedMetricRule` | Derived-metric definitions reference real metrics; offset windows are valid. | ERROR + WARNING |
| `metrics.py` | `ConversionMetricRule` | Conversion metrics (base/conversion measures, entity, time-window) are properly configured. | ERROR + WARNING |
| `metrics.py` | `SimpleMetricExprRule` | Simple-metric `expr` is configured correctly for the legacy spec. | ERROR + WARNING |
| `non_empty.py` | `NonEmptyRule` | The manifest has at least one semantic model and at least one metric. | ERROR |
| `primary_entity.py` | `PrimaryEntityRule` | Primary entity is set on every semantic model (or derivable). | ERROR |
| `reserved_keywords.py` | `ReservedKeywordsRule` | Element names selected by name (not `expr`) aren't reserved (e.g. SQL keywords, `metric_time`). | ERROR |
| `saved_query.py` | `SavedQueryRule` | Saved query fields parse and refer to real metrics/groupBys. | ERROR |
| `semantic_models.py` | `SemanticModelValidityWindowRule` | Validity windows are proper (one start, one end, both time dims). | ERROR |
| `semantic_models.py` | `SemanticModelDefaultsRule` | Semantic-model `defaults.agg_time_dimension` is consistent with measures. | ERROR |
| `time_dimension_has_granularity.py` | `TimeDimensionHasGranularityRule` | Every time dimension declares a granularity. | FUTURE_ERROR |
| `time_spines.py` | `TimeSpineRule` | Time spines configured properly (one per granularity, smallest grain ≤ smallest dim grain). | WARNING |
| `unique_valid_name.py` | `UniqueAndValidNameRule` | All names valid characters; no duplicates within a semantic model. | ERROR |
| `unique_valid_name.py` | `PrimaryEntityDimensionPairs` | Every (primary entity, dimension) pair is unique across the manifest. | ERROR |
| `where_filters.py` | `WhereFiltersAreParseable` | Every `WhereFilter` template parses (Jinja-shaped). | WARNING |

**Total active rules**: 28.

## Helper files (no rule class — supporting infrastructure)

| File | Purpose |
|---|---|
| `validator_helpers.py` | `ValidationIssue` / `ValidationError` / `ValidationWarning` / `ValidationFutureError` types, `ValidationIssueLevel` enum, `validate_safely` decorator, `ValidationIssueSet`, `SemanticManifestValidationResults`, `SemanticManifestValidationException`. |
| `shared_measure_and_metric_helpers.py` | Helpers shared between `measures.py` and `metrics.py` (since simple metrics replaced measures). |

## Inactive rule (defined but not in `DEFAULT_RULES`)

| File | Rule class | Why inactive |
|---|---|---|
| `common_entities.py` | `CommonEntitysRule` | Would warn about entities that exist on only one semantic model. metricflow ships it disabled (probably noisy in dbt projects). We do **not** port it. |

## Implementation notes for the Kotlin port

1. **Single Kotlin class per Python class.** Each Python class becomes one Kotlin `class` implementing a `SemanticManifestValidationRule` interface. No magic; the rule list in the Validator constructor is just a `listOf(...)` of instances.
2. **Severities are issue-level, not rule-level.** The Kotlin `ValidationIssue` `sealed interface` (see `data-model-mapping.md` § Special cases) has the level encoded in the type. Validators emit a `List<ValidationIssue>` and downstream code filters by `is ValidationError` etc.
3. **`validate_safely` decorator → Kotlin extension.** The Python decorator catches exceptions and packages them as a "validation infrastructure failed" issue. In Kotlin we provide `ValidationContext.runSafely { ... }` that wraps a block similarly.
4. **`ProcessPoolExecutor` parallelism dropped.** Python's `_validate_multi_process` runs rules in parallel processes for performance. In Kotlin we run them sequentially in a single thread (or, if needed later, with `coroutineScope { rules.map { async { it.validate(manifest) } }.awaitAll() }`). Same observable behaviour, simpler.
5. **`copy.deepcopy(manifest)` in `checked_validations` → drop.** The deep copy is to defend against rules that mutate the manifest. Our Kotlin manifest is immutable (`data class` with `val`s), so deep-copy is not needed.
6. **The 28 rules port well in parallel.** Each is a self-contained `validate_manifest` function that takes the immutable manifest and returns issues. Wave 2 of the porting plan has all 28 as parallelizable PRs.

## Total LOC of the validation surface

```bash
find python_oracle/upstream/metricflow_semantic_interfaces/validations -name "*.py" | xargs wc -l | tail -1
# 4643 LOC across 21 files (20 reachable: 4560 LOC; 1 unreachable common_entities.py: 83 LOC)
```

`metrics.py` is the largest single file at ~1.4k LOC, with 7 rule classes; expect it to split into one file per rule on the Kotlin side.
