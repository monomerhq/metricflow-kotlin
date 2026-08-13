# EXTRACTION_NOTES.md

Notes on how `python_oracle/upstream/tests_metricflow` builds its test fixtures and
snapshots. Captured during Phase 1b corpus extraction. Future agents should read
this before adding new corpus extractors so they don't rebuild understanding from scratch.

## How manifests are loaded

* YAML files live under
  `python_oracle/upstream/metricflow_semantics/test_helpers/semantic_manifest_yamls/<name>/`
  - `project_configuration.yaml` (must contain `time_spines` and/or `time_spine_table_configurations`)
  - `metrics.yaml` and/or per-metric YAML (multi-doc `---` separated)
  - `semantic_models/*.yaml` (one per semantic model) — _or_ a single `manifest.yaml`
  - optional `saved_queries.yaml`
* They use `$source_schema` placeholders that the test framework resolves at runtime
  via `string.Template.substitute({"source_schema": mf_source_schema})`.
* Loader: `metricflow_semantic_interfaces.parsing.dir_to_model.parse_directory_of_yaml_files_to_semantic_manifest(directory, template_mapping=...)`
* The high-level helper in upstream:
  `metricflow_semantics.test_helpers.manifest_helpers.mf_load_manifest_from_yaml_directory`
* The fixture `template_mapping` is set to `{"source_schema": mf_test_configuration.mf_source_schema}`.
  `mf_source_schema` defaults to a per-session random schema name like `mf_test_2024_03_15_abcdef0123`
  (27 chars). The session-random schema is what gets stamped into SQL output and snapshots
  later replace it with `*`s of the same length (see snapshot_helpers
  `make_schema_replacement_function`).

## How tests render SQL and assert against snapshots

The repo has *two* end-points that produce SQL snapshots, with subtly different semantics:

1. **`tests_metricflow/integration/test_rendered_query.py`** — calls
   `MetricFlowEngine.explain(MetricFlowQueryRequest.create(...))`. Produces a
   single snapshot per dialect (`snapshots/test_rendered_query.py/str/<Dialect>/<test>__<id>.sql`).
   This is what the oracle CLI's `explain` subcommand reproduces.

2. **`tests_metricflow/query_rendering/*.py`** — calls
   `DataflowToSqlPlanConverter.convert_to_sql_plan(...)` directly through the
   helper `render_and_check` (in `query_rendering/compare_rendered_query.py`).
   Produces TWO snapshots per case (`plan0` and `plan0_optimized`). The
   `_optimized.sql` is the one most similar to what `engine.explain` produces
   (it runs all enabled `DataflowPlanOptimization`s + the default SQL
   optimizer level).

The oracle CLI's `explain` subcommand goes through `MetricFlowEngine.explain`,
which:
* builds the dataflow plan with `dataflow_plan_optimizations=None` (defaults to
  `DataflowPlanOptimization.enabled_optimizations()` if unset — see
  `MetricFlowEngine.explain`).
* converts to SQL with `SqlOptimizationLevel.O5` (the default).

So **`plan0_optimized` snapshots should match `engine.explain` SQL exactly**
when the query inputs are equivalent. The non-optimized `plan0.sql` will not.

Snapshot files include a YAML-style header (test_name, test_filename, sql_engine,
optionally docstring/expectation_description). Then a `---` separator, then the
actual SQL. See `metricflow_semantics/test_helpers/snapshot_helpers.py` for the
exact format (`_HEADER_END_MARKER = "---"`).

## Schema placeholder handling

When the engine renders SQL it stamps the real source schema (the session-random
one set up by `mf_test_configuration`) into the SQL. The snapshot helper then
applies `make_schema_replacement_function(system_schema, source_schema)` which
replaces the random string with `*` of the same length. Therefore snapshots like
`***************************.fct_bookings` have 27 stars.

For corpus reproduction we:
* convert the YAMLs with a known schema placeholder `MF_CORPUS_SCHEMA_27_CHARS_` (27 chars exactly, matches default schema length)
* run the oracle, get SQL containing that schema
* the normalizer collapses any "schema_name string" of len 27 chars composed of
  identifier characters back to `*` of length 27 -- but actually simpler: we
  just replace the literal known schema name with the same number of stars,
  matching the upstream snapshot helper exactly.

## SnapshotConfiguration / where test snapshots live

`metricflow_semantics/test_helpers/snapshot_helpers.py::_snapshot_path_prefix` lays out:

```
<repo_root>/tests_metricflow/snapshots/<test_module_filename>.py/<group_id>/[<dialect>/]<test_name>__<snapshot_id>.<ext>
```

So you can map (test_filename, group_id, sql_engine, test_name, snapshot_id) →
file path. `group_id` is usually `SqlPlan` for query_rendering tests and `str`
for plain `assert_str_snapshot_equal` callers like the integration tests.

## Integration test cases (YAML-defined)

`tests_metricflow/integration/test_cases/*.yaml` — the integration test cases
are declarative YAML files. Each defines a metric query plus a "check query"
that produces the same expected output via raw SQL. These tests run end-to-end
(`engine.query()` not `engine.explain()`) and compare DataTable rows, so their
expected outputs are NOT SQL strings. They're useful as **inputs** to
`explain` (we can build a request from the metrics/group_bys/etc.) but they do
not provide a Python-rendered SQL ground truth.

We have decided NOT to use these as primary corpus for SQL diff. Use them only
for non-SQL subcommands (or skip).

## Validation corpus

Tests under `tests_metricflow_semantic_interfaces/validations/*.py` use *inline
YAML strings* via `textwrap.dedent(...)` rather than file-based fixtures. Each
test:
* constructs a `PydanticSemanticManifest` (or inline YAML)
* runs `SemanticManifestValidator(<rules>).checked_validations(manifest)`
* asserts on the resulting exception / error messages

Extracting these statically requires literal-AST inspection of each test body.
That's too noisy for Phase 1b — we instead seed the validation corpus by:

1. Running `validate_manifest` against every YAML manifest under
   `semantic_manifest_yamls/` (most are expected to validate clean).
2. Adding a handful of hand-curated invalid manifests derived from the
   `config_linter_manifest` (already-flagged intentional errors).
3. Adding the minimal_invalid_manifest fixture that lives in
   `python_oracle/tests/fixtures/`.

This gives ≥10 validate_manifest cases without trying to fork each
ad-hoc test.

## Subcommand → corpus shape

| Subcommand | Input | Expected output kind |
|---|---|---|
| `explain` | manifest + `args.metric_names`/`group_by_names`/... | SQL string per dialect |
| `list_metrics` | manifest (+ `include_dimensions`) | JSON `{metrics: [...]}` |
| `list_dimensions` | manifest (+ `metric_names`) | JSON `{dimensions: [...]}` |
| `entities_for_metrics` | manifest + `metric_names` | JSON `{entities: [...]}` |
| `list_group_bys` | manifest (+ `metric_names`/`order_by`/...) | JSON `{dimensions, entities}` |
| `list_saved_queries` | manifest | JSON `{saved_queries: [...]}` |
| `explain_get_dimension_values` | manifest + `metric_names`+`get_group_by_values` | SQL string per dialect |
| `validate_manifest` | manifest | JSON `{issues, error_count, ...}` |

The "expected output" of non-SQL subcommands is the JSON the oracle produces on
a known-good manifest. Phase 1b seeds these by running the oracle once and
snapshotting the output -- they become the regression baseline that Phase 3
Kotlin needs to match.

## Key invariants and caveats

* `MetricFlowEngine.explain` includes a `consistent_id_enumeration=False` default,
  which means subquery aliases like `subq_3` are assigned via a global counter.
  Across runs *in the same process* they're consistent because the engine is
  built once. Across two distinct Python processes they're also consistent for
  the same engine_factory inputs (the ID generator is fresh per process and
  metricflow assigns IDs deterministically given a fixed dataflow plan build).
  In short: our oracle CLI invocations are deterministic; comparing oracle
  output to its own prior output is exact-match.

* Snapshots for *upstream* tests have aliases like `subq_10` because each
  upstream test session shares an `IdNumberSpace` with other tests and
  pre-bumps the counter. So if we want to compare oracle outputs to upstream
  snapshots we'd have to match that ID skew -- not always feasible.
  Therefore the primary expected-output strategy is **snapshot oracle output on
  the first run** rather than reuse upstream snapshots.

* The oracle's `serialize.py` sorts dimension/entity arrays. Re-runs are
  deterministic. So JSON-shape comparisons are byte-stable.

## Dialects available

`SqlEngine` enum lists 7: `BIGQUERY`, `DATABRICKS`, `DUCKDB`, `POSTGRES`, `REDSHIFT`, `SNOWFLAKE`, `TRINO`.
Snapshots exist for all 7 in `test_query_rendering.py`. Our oracle accepts a
`sql_engine` field at the top level of the input envelope.

## Conclusion: extraction strategy

We use a **self-snapshotting** approach:

1. Convert every YAML manifest under `semantic_manifest_yamls/` into a JSON
   envelope (using our oracle's `build_manifest` shape).
2. Define a set of `args` permutations for each manifest (curated from existing
   integration tests + naive enumeration of metrics/group_bys).
3. For each (manifest, args, dialect) triple, run the oracle once and freeze the
   output as `expected/<dialect>.sql` or `expected.json`.
4. The harness `run_oracle.py` re-runs and diffs against the frozen output.

This gives reproducible, exact-match corpus without depending on upstream
snapshot peculiarities. Phase 3 Kotlin's job is then to match the oracle's
output that the corpus has frozen, not the upstream snapshots directly.

We still cross-reference upstream `_optimized.sql` snapshots after extraction
as a sanity check: for the simple_manifest tests we expect close shape match
(after normalizer schema substitution + alias renumbering tolerance).
