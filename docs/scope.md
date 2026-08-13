# Phase 0 — Scope

What in `python_oracle/upstream/` we have to port to Kotlin, what we drop, and how big the job actually is.

## TL;DR

- **Total** Python sources across the three packages: **574 files / 67,666 LOC**
- **Reachable** from our PORT SCOPE entry points: **477 files / 60,346 LOC** (≈89% of total)
- **Execution-only excluded**: **1 file / 89 LOC** (`metricflow/execution/executor.py`)
- **Other unreachable** (dbt converters, YAML parsing, test helpers, graphviz formatters, etc.): **96 files / 7,231 LOC**

`477 + 1 + 96 = 574 files` ✓ — `60,346 + 89 + 7,231 = 67,666 LOC` ✓

The reachable LOC is what we have to translate to Kotlin. With the usual 1.3–1.7× expansion factor for typed Kotlin data classes vs Pydantic, expect **~78k–103k Kotlin LOC** to land. (FEASIBILITY.md §2's 88k–115k estimate stands; the lower bound is ~10k tighter because reachability prunes converters/parsing/test helpers.)

## Per-package reachable vs unreachable

Run from `python_oracle/upstream/`:

```bash
find metricflow                       -name "*.py" | xargs wc -l | tail -1   # 26009
find metricflow_semantics             -name "*.py" | xargs wc -l | tail -1   # 28681
find metricflow_semantic_interfaces   -name "*.py" | xargs wc -l | tail -1   # 12976
```

| Package | Total files | Total LOC | Reachable files | Reachable LOC | Reach % |
|---|---:|---:|---:|---:|---:|
| `metricflow` | 148 | 26,009 | 132 | 23,932 | 92% |
| `metricflow_semantics` | 306 | 28,681 | 237 | 25,202 | 88% |
| `metricflow_semantic_interfaces` | 120 | 12,976 | 108 | 11,212 | 86% |
| **Total** | **574** | **67,666** | **477** | **60,346** | **89%** |

## Reachable LOC by second-level directory

This is the porting workload. Numbers are LOC inside the reachable set. The third column hints at the Kotlin module bucket from `module-mapping.md`. (Some directories appear partially because some files inside them are unreachable — for example `metricflow/sql_request/` is fully unreachable, omitted here.)

| Python directory | Files | LOC | Kotlin bucket |
|---|---:|---:|---|
| `metricflow_semantic_interfaces/<root>` | 7 | 831 | `domain.manifest.model` (shared utilities — `references`, `enum_extension`, `errors`, `dataclass_serialization`) |
| `metricflow_semantic_interfaces/implementations` | 20 | 1,448 | `domain.manifest.model` (Pydantic implementations) |
| `metricflow_semantic_interfaces/protocols` | 19 | 1,520 | `domain.manifest.model` (interface contracts) |
| `metricflow_semantic_interfaces/type_enums` | 11 | 252 | `domain.manifest.model.enums` |
| `metricflow_semantic_interfaces/naming` | 3 | 106 | `domain.manifest.model` |
| `metricflow_semantic_interfaces/parsing` | 10 | 965 | `domain.manifest.model` (kept files: `where_filter/*` parsers, `text_input/*` only — YAML/dbt parsing is unreachable) |
| `metricflow_semantic_interfaces/transformations` | 18 | 1,530 | `domain.manifest.transformation` |
| `metricflow_semantic_interfaces/validations` | 20 | 4,560 | `domain.manifest.validation` |
| `metricflow/data_table` | 4 | 356 | `domain.datatable` |
| `metricflow/protocols` | 2 | 113 | `domain.sqlclient` |
| `metricflow/telemetry` | 5 | 354 | `common.telemetry` |
| `metricflow/validation` | 1 | 80 | `domain.lookup` (only `validation_helpers.py` reachable) |
| `metricflow/engine` | 4 | 1,212 | `application.engine` (incl. empty `__init__.py`) |
| `metricflow/execution` | 4 | 490 | `application.engine` (see SQL-wrapping note below) |
| `metricflow/dataflow` | 43 | 7,261 | `domain.dataflow` |
| `metricflow/dataset` | 5 | 951 | `domain.dataflow` |
| `metricflow/metric_evaluation` | 17 | 3,070 | `domain.dataflow` |
| `metricflow/plan_conversion` | 16 | 5,775 | `domain.plan_conversion` |
| `metricflow/sql` (excl render) | 19 | 2,474 | `domain.sql.plan` + `domain.sql.optimizer` |
| `metricflow/sql/render` | 11 | 1,796 | `infrastructure.sql.render.{base,dialects}` |
| `metricflow_semantics/<root>` | 3 | 524 | `domain.spec` (`instances.py`, `aggregation_properties.py`) |
| `metricflow_semantics/dag` | 5 | 696 | `common.toolkit` (`mf_dag`, `id_prefix`, etc.) |
| `metricflow_semantics/errors` | 3 | 158 | `common.toolkit` |
| `metricflow_semantics/filters` | 2 | 107 | `common.time` (`TimeRangeConstraint`) |
| `metricflow_semantics/model` | 15 | 1,528 | `domain.lookup` |
| `metricflow_semantics/naming` | 7 | 736 | `domain.spec` |
| `metricflow_semantics/protocols` | 1 | 116 | `domain.query` |
| `metricflow_semantics/query` | 58 | 5,854 | `domain.query` |
| `metricflow_semantics/semantic_graph` | 43 | 5,762 | `domain.semantic_graph` |
| `metricflow_semantics/specs` | 39 | 3,457 | `domain.spec` |
| `metricflow_semantics/sql` | 6 | 2,208 | `domain.spec.bind` (`SqlBindParameterSet`, `SqlTable` — referenced everywhere) |
| `metricflow_semantics/time` | 7 | 468 | `common.time` |
| `metricflow_semantics/toolkit` | 48 | 3,588 | `common.toolkit` |
| **Reachable total** | **477** | **60,346** | |

## Excluded — execution-only

Per CLAUDE.md, the engine produces SQL; it does not run SQL. Anything purely executor-shaped is excluded.

| File | LOC | Why |
|---|---:|---|
| `metricflow/execution/executor.py` | 89 | `SequentialPlanExecutor` — runs `ExecutionPlanTask`s. Only used by `MetricFlowEngine.query()`, which is out of scope. |

**Note on the rest of `metricflow/execution/`** — the orchestrator's PORT SCOPE says "exclude `metricflow/execution/` (entire package)", but reachability shows three of its four files **are** reached from `MetricFlowEngine.explain()` (the in-scope SQL-generation entry):

| File | LOC | Role |
|---|---:|---|
| `metricflow/execution/__init__.py` | 0 | empty |
| `metricflow/execution/convert_to_execution_plan.py` | 16 | dataclass holding `ConvertToSqlPlanResult + RenderSqlResult + ExecutionPlan` returned from `_create_execution_plan` |
| `metricflow/execution/dataflow_to_execution.py` | 229 | `DataflowToExecutionPlanConverter` — calls `DataflowToSqlPlanConverter` + `SqlPlanRenderer`, then **wraps** the rendered SQL in an `ExecutionPlan` task. The visitor's `visit_*` overrides for non-write nodes raise `NotImplementedError`; only the Write nodes actually do work. |
| `metricflow/execution/execution_plan.py` | 245 | `ExecutionPlan`, `SqlStatement`, `SelectSqlQueryToDataTableTask`, `SelectSqlQueryToTableTask`, `TaskExecutionResult`. The task `execute()` method runs SQL — but `explain()` never calls it; the `SqlStatement` carrying the rendered SQL is what's returned. |

For the Kotlin port these collapse to **two small types** in `application.engine`: `MetricFlowExplainResult { querySpec, dataflowPlan, sql, bindParameters }` and `SqlStatement { sql, bindParameters }`. We do not need a Visitor over Write nodes at all — `explain()` always lands at a write node, so we can take the SQL from the converter directly.

→ See "Proposed updates to FEASIBILITY.md" in the report for the suggested wording fix.

## Excluded — other unreachable (96 files / 7,231 LOC)

These are not reached from any of our entry points and represent capabilities we do not need:

| Cluster | Files | LOC | Why excluded |
|---|---:|---:|---|
| `metricflow_semantics/test_helpers/*` | 45 | 2,204 | Synthetic manifest generators, snapshot helpers, profiling — test-only. |
| `metricflow_semantic_interfaces/parsing/{schemas,dir_to_model,schema_validator,generate_json_schema_file,objects}.py` and `parsing/where_filter/where_filter_{entity,dimension}.py` | 8 | 1,389 | YAML/dbt-manifest hydration. We hydrate manifests from JSON via `kotlinx-serialization`, not by parsing YAML and JSON Schema. |
| `metricflow/converters/{msi_to_osi,osi_to_msi,models,filter_utils,expression_utils,__init__}.py` | 6 | 1,254 | OSI ↔ MSI semantic-spec converters (Open Semantic Interchange). Not on our query→SQL path. |
| `metricflow_semantics/toolkit/mf_graph/formatting/*` | 7 | 862 | DOT/Graphviz/SVG visualisation of DAGs. We log structured text instead. |
| `metricflow/validation/data_warehouse_model_validator.py` | 1 | 664 | Hits a real DW to validate models. Out of scope (no execution). |
| `metricflow_semantic_interfaces/test_utils.py` | 1 | 252 | Test fixtures only. |
| `metricflow_semantics/api/v0_1/*` | 3 | 85 | Saved-query dependency resolver — used by `mf` CLI, not by our entry points. |
| `metricflow_semantic_interfaces/validations/common_entities.py` | 1 | 83 | `CommonEntitysRule` — defined but **not** in `SemanticManifestValidator.DEFAULT_RULES`. (Confirm: `grep CommonEntitysRule semantic_manifest_validator.py` → no hit.) |
| `metricflow_semantics/model/dbt_manifest_parser.py` | 1 | 52 | dbt-specific manifest hydration. |
| `metricflow_semantics/dag/dag_visualization.py` | 1 | 51 | DAG SVG rendering. |
| `metricflow/sql/sql_column.py` | 1 | 51 | Defined but unused (no production caller; only in `tests_metricflow`). |
| `metricflow/sql_request/*` | 2 | 16 | Used only by SqlClient execution path. |
| Other small orphans (rule_set, transform_rule, names, boolean_measure, cumulative_type_params, etc.) | 21 | ~316 | Additional transformation rules NOT chained by `PydanticSemanticManifestTransformRuleSet`, plus empty `__init__.py`s, plus a few helper files. |

The full reachable / unreachable / executor-excluded file lists are derivable from the AST tracer at [`docs/scripts/reach.py`](scripts/reach.py); we do not check those lists into the repo because they are derivable.

## Reproducible commands

```bash
# Per-package totals (reproduces the table at top):
cd python_oracle/upstream
find metricflow                       -name "*.py" | xargs wc -l | tail -1
find metricflow_semantics             -name "*.py" | xargs wc -l | tail -1
find metricflow_semantic_interfaces   -name "*.py" | xargs wc -l | tail -1
# 26009 / 28681 / 12976 LOC, 148 / 306 / 120 files; 67666 LOC, 574 files total

# Per second-level directory:
for d in $(find metricflow metricflow_semantics metricflow_semantic_interfaces \
              -name "*.py" -type f | sed 's|/[^/]*$||' | sort -u); do
  count=$(find "$d" -maxdepth 1 -name "*.py" | wc -l | tr -d ' ')
  loc=$(find "$d" -maxdepth 1 -name "*.py" -exec wc -l {} + 2>/dev/null | tail -1 | awk '{print $1}')
  printf "%-70s files=%-4s loc=%s\n" "$d" "$count" "$loc"
done
```

The reachability trace was implemented as an AST-level static analysis (Python 3.12), checked into [`docs/scripts/reach.py`](scripts/reach.py) so the 60,346 LOC / 477 file figures are reproducible from this repo alone. Run with `python3 docs/scripts/reach.py` from the project root.

Entry points encoded in the script:

- `metricflow.engine.metricflow_engine` (covers all 7 SQL-generation methods plus the shared helpers)
- `metricflow_semantic_interfaces.validations.semantic_manifest_validator` (covers `checked_validations`)
- `metricflow.engine.models` (`Dimension.from_pydantic`, `Entity.from_pydantic`, `Metric.from_pydantic`, `SavedQuery.from_pydantic` — used by the engine)
- `metricflow.sql.render.{trino, big_query, snowflake, databricks, redshift, duckdb_renderer, postgres, sql_plan_renderer}` (all dialects + base; PORT SCOPE explicit)
- `metricflow_semantic_interfaces.transformations.{semantic_manifest_transformer, pydantic_rule_set}` (manifest hydration pipeline; production callers always run this before validation)

Pruned at the boundary: `metricflow.execution.executor` (and only that file).

The trace walks `from X import Y` / `import X` statements, resolves `Y` against the module index when `X.Y` is itself a module, and over-includes when ambiguous. Modules in any other Python package (`typing_extensions`, stdlib, `pydantic`, `msi_pydantic_shim`) are ignored.

## Judgment calls

1. **`metricflow/execution/{convert_to_execution_plan, dataflow_to_execution, execution_plan}.py` — kept reachable.** They are imported by `MetricFlowEngine.explain()`. Their *role* is execution-wrapping, but the sub-role used on the in-scope path is "structurally bind a rendered SQL string + bind parameters with the dataflow plan". For Kotlin we collapse this into a two-record return type. Marking these files unreachable would have left `explain()` undefined.

2. **`metricflow_semantic_interfaces/parsing/where_filter/where_filter_call.py`, `where_filter_call_processor.py`, `where_filter_call_visitor.py`, `where_filter_intersection_resolver.py`** — reachable. We need to parse where-filter expressions like `Metric('x', ['y'])` from saved queries. (`where_filter_dimension.py` and `where_filter_entity.py` are NOT reachable — they're only used by the `mf` CLI.)

3. **`metricflow/sql/sql_column.py` — unreachable, but easy to mistake for in-scope.** It defines `SqlColumn`, but our SQL plan model uses `SqlColumnReferenceExpression` (in `metricflow/sql/sql_exprs.py`, reachable). Confirmed by grep: `SqlColumn` (without `Reference`) is referenced only from `tests_metricflow`.

4. **`CommonEntitysRule` (`validations/common_entities.py`) is unreachable.** Defined but not in `DEFAULT_RULES` (line 75–104 of `semantic_manifest_validator.py`). It would warn about entities used on only one semantic model; metricflow ships it disabled. We do not port it. **This means the FEASIBILITY.md "11 rules" figure is significantly wrong — the active set is 28 rules across 16 rule-bearing files. See `validation-rules-inventory.md`.**

5. **`metricflow/dataflow/optimizer/dataflow_optimizer_factory.py`** — reachable (referenced from engine). The whole `dataflow/optimizer/source_scan/` subtree (4 files / 974 LOC) is reachable.

6. **All 7 dialect renderers + Default — kept as entries.** Walking only from `MetricFlowEngine` would miss them, since the engine takes a `SqlClient` whose `sql_plan_renderer` is supplied externally. PORT SCOPE explicitly names every dialect, so we add them as entry points. This pulls them and any branch logic specific to them (`expr_renderer`, `rendering_constants`).

7. **`metricflow/validation/validation_helpers.py`** — reachable (data-source validity helpers), 80 LOC. The other two files in `metricflow/validation/` (`data_warehouse_model_validator.py`, `__init__.py`) are not.
