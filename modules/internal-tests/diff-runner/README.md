# `:integration:diff-runner`

Kotlin port of `harness/run_diff.py`. Iterates `corpus/<case>/`, invokes the
Kotlin engine in-process, and compares the result against either
`expected.json` (non-SQL subcommands) or `expected/<dialect>.sql` (explain /
explain_get_dimension_values) from the Python oracle.

**Why direct engine calls instead of gRPC?** Pre-W10 the diff loop ran through
the gRPC channel to exercise the wire. W10 changed that — the runner now calls
`MetricFlowEngine` and the JSON serialiser directly. Rationale:

- The differential acceptance bar is "Python-oracle JSON == Kotlin canonical
  JSON" (sorted by stable identifying keys). The wire shape is exercised by
  `EngineBuildSmoke` in `:application:engine` and is structurally identical.
- The wire path required encoding manifest sections as JSON strings, then
  decoding them back to domain types — a round-trip with no informational
  value for the diff. Direct calls cut latency and complexity.
- The diff comparison wants `JsonElement` equality (with normalisation),
  which is awkward to recover from the `*_json` proto strings.

**Run**
```
./gradlew :integration:diff-runner:run
# Filter to one subcommand or case:
./gradlew :integration:diff-runner:run --args="--subcommand=validate_manifest"
./gradlew :integration:diff-runner:run --args="--case=list_metrics__simple__with_dimensions"
```

Working directory is forced to the repository root by the module's
`build.gradle.kts` so `corpus/` paths resolve.

**Exit code**
- `0` if every case is either `PASS` or `UNIMPLEMENTED`.
- non-zero if any case ends in `FAIL` or `ERROR`.

## Per-subcommand comparison routing

The case runner picks one of two comparison paths per subcommand:

| Subcommand                       | Expected layout              | Comparison       |
|----------------------------------|------------------------------|------------------|
| `explain`                        | `expected/<dialect>.sql`     | Per-dialect SQL diff (case-level PASS / FAIL aggregation) |
| `explain_get_dimension_values`   | `expected/<dialect>.sql`     | Same as `explain` |
| Everything else                  | `expected.json`              | Canonical JSON structural compare |

The per-dialect SQL path (added in W14b):

1. Enumerate `expected/<dialect>.sql` files (sorted alphabetically).
2. For each dialect, map the file stem to a `SqlEngine` enum value
   (`bigquery → BIGQUERY`, etc.) and invoke
   `MetricFlowEngine.explain(request.copy(dialect = <enum>))`. The pipeline
   picks the matching dialect renderer (see `ExplainPipeline.rendererFor`).
3. Normalize both strings via `SqlNormalizer` (3 rules: line endings,
   trailing whitespace, blank lines — port of `harness/sql_norm/`).
4. Byte-compare.
5. **Aggregation is case-level**: PASS iff every dialect matched; FAIL
   otherwise with a per-dialect breakdown (`"3/7 dialects failed (bigquery,
   databricks, snowflake). First mismatch: bigquery line 13: …"`). One row
   per case keeps the report compact; per-dialect detail is in the mismatch
   summary.

The orderer also acts as a column allow-list (see `ExplainPipeline.renderSql`):
without supplying an `OutputColumnOrderer`, the WriteToResultDataTableNode
visitor emits every column in the dataset, and the SqlColumnPrunerOptimizer
keeps all of them in the outer SELECT. With the input-order-preserving
type-grouped orderer, only the columns the query input named survive — which
is what's necessary to match Python's snapshots.

## Comparison rules

The case runner canonicalises both sides before structural equality:

1. **Object keys sorted alphabetically.** Python's `json.dumps(..., sort_keys=False)`
   emits insertion order; Kotlin's kotlinx-serialization is also insertion-order.
   Canonicalising removes the dependency on field-emission order.
2. **Lists keyed on an identifying child are sorted by that child** before
   comparison. The list of identifying keys is hard-coded in
   `CaseRunner.LIST_KEYS_WITH_SORT_KEY`:
   - `metrics` by `name`
   - `dimensions` by `dunder_name`, then `semantic_model_name`
   - `entities` by `name`, then `semantic_model_name`
   - `saved_queries` by `name`
   - `issues` by `level`, `message`, `context_str`
   - `exports` by `name`
3. **Mismatch report**: the first differing leaf is reported with its JSON path
   (e.g. `$.metrics[0].dimensions[3].dunder_name`) so the cause is visible
   without scrolling through hundreds of lines of JSON.

## Subcommand status (W14b snapshot)

| Subcommand | PASS | FAIL | UNIMPLEMENTED | Notes |
|---|---:|---:|---:|---|
| `validate_manifest` | 19 | 0 | 0 | Full parity with Python's 28-rule validator. |
| `list_saved_queries` | 17 | 0 | 0 | Direct manifest iteration. |
| `list_dimensions` | 19 | 0 | 0 | Multi-hop dimensions surfaced via the W11 DFS resolver. |
| `list_metrics` | 18 | 0 | 0 | `include_dimensions=true` also passes. |
| `entities_for_metrics` | 3 | 0 | 0 | Canonical origin from the resolver's `joined_model_ids[-1]`. |
| `list_group_bys` | 5 | 0 | 0 | Composition of `list_dimensions` + `entities_for_metrics`. |
| `explain` | 15 | 3 | 11 | SIMPLE happy path PASSes; CUMULATIVE / DERIVED / RATIO / CONVERSION still W14c-deferred (UNIMPLEMENTED). The 3 FAILs are W14a parser-residue bugs (order_by / where_constraint_strs / time_constraint_start are passed to the parser but the parser body ignores them). |
| `explain_get_dimension_values` | 0 | 1 | 1 | Distinct-values path is W14c (separate `build_plan_for_distinct_values` route). |

Totals: **96 PASS / 4 FAIL / 12 UNIMPLEMENTED** out of 112. Up from W11
baseline of 81 PASS / 0 FAIL / 31 UNIMPLEMENTED — W14b delivers 15 new
explain PASSes.

### W11 → W14b delta

- **W14a** (`MetricFlowQueryResolver.resolveQuery` body) moved every explain
  case from UNIMPLEMENTED-at-parser-stage to UNIMPLEMENTED-at-builder-stage.
  Numerically that wave was neutral on the PASS column.
- **W14b** filled the `DataflowPlanBuilder.buildPlan` body for the SIMPLE
  happy path and wired `:application:engine` to dispatch parser → builder →
  converter → renderer. 15 explain corpus cases moved from UNIMPLEMENTED to
  PASS. The remaining 11 UNIMPLs are honest deferrals for non-SIMPLE metric
  shapes (`MetricType.CUMULATIVE | DERIVED | RATIO | CONVERSION`); the 4
  FAILs are all W14a-parser residue (order_by_names /
  where_constraint_strs / time_constraint_* are accepted by the parser
  surface but the parser body drops them, so the resolved querySpec lacks
  these fields and the builder produces SQL without them — matching what
  Python emits for the *no-filter* case, but the corpus expects the
  *with-filter* case).
