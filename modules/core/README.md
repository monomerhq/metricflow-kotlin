# :core — metricflow-core

Phase-5 consolidation of every `:common:*` and `:domain:*` module from Phase 3.

This is the **single library JAR** consumers reach for: manifest model + validation +
specs + dataflow plan + SQL plan + planner + (default) renderer + engine facade
plumbing. Dialect renderers and the optional gRPC server live in their own
artifacts (see PHASE_5_PLAN.md).

## What lives here

| Package | Phase-3 origin | Responsibility |
|---|---|---|
| `common.util`, `common.dag`, `common.graph`, `common.logging`, `common.errors` | `:common:toolkit` | Generic helpers — caches, DAG/graph algorithms, structured-logging adapters |
| `common.util.collections` | `:common:toolkit` | Frozen-list / id-set primitives |
| `common.time` | `:common:time` | `TimeRangeConstraint`, granularity arithmetic |
| `common.telemetry` | `:common:telemetry` | `Tracer`/`Span` interfaces only — no implementation |
| `domain.manifest.model` | `:domain:manifest:model` | Pydantic-equivalent semantic-manifest data classes + JSON round-trip |
| `domain.manifest.transformation` | `:domain:manifest:transformation` | Pre-validation manifest normalisations (rule pipeline) |
| `domain.manifest.validation` | `:domain:manifest:validation` | `SemanticManifestValidator` + 28-rule corpus |
| `domain.datatable` | `:domain:datatable` | `DataTable` (Python pandas DataFrame substitute) |
| `domain.spec`, `domain.spec.bind`, `domain.spec.naming` | `:domain:spec`, `:domain:spec:bind` | `LinkableInstanceSpec`, `MetricFlowQuerySpec`, dunder-name resolution |
| `domain.lookup` | `:domain:lookup` | `SemanticManifestLookup`, metric/dimension/entity reverse indexes |
| `domain.semantic_graph` | `:domain:semantic-graph` | Semantic graph + BFS/DFS resolver |
| `domain.query` | `:domain:query` | `MetricFlowQueryParser`, where-filter spec factory + template renderer |
| `domain.dataflow` | `:domain:dataflow` | `DataflowPlan`, plan nodes, dataflow plan builder |
| `domain.metric_evaluation` | `:domain:metric-evaluation` | Recursive metric-evaluation planner (`DepthFirstSearchMetricEvaluationPlanner`) |
| `domain.plan_conversion` | `:domain:plan-conversion` | `DataflowToSqlPlanConverter` + 23 visit methods |
| `domain.sql.plan` | `:domain:sql:plan` | SQL plan AST |
| `domain.sql.optimizer` | `:domain:sql:optimizer` | Column pruner / predicate pushdown / where-filter normalisation |
| `domain.sql.render` | `:domain:sql:render` + absorbed `:domain:sqlclient`, `:infrastructure:sql:render:base`, `:infrastructure:sql:render:default` | Renderer interfaces, default ANSI renderer, `SqlEngine` enum, `SqlClient` port |

## What does NOT live here

- Dialect-specific renderers — see `:infrastructure:sql:render:<dialect>`.
- gRPC server / proto adapters — see `:application:engine` (until Step 6 splits them out).
- Diff-runner — see `:integration:diff-runner` (until Step 7 moves it to `:internal-tests`).

## Public API

See [`docs/PUBLIC_API.md`](../../docs/PUBLIC_API.md) for the consumer-facing surface.
Everything else is implementation detail and may be marked `internal` in a future
Phase 5 sweep.

## Migration history

```
Phase 3 (33 modules)     Phase 5 (this module)
─────────────────────    ──────────────────────
:common:toolkit       \
:common:time          ─┐
:common:telemetry      \
:domain:manifest:model  \
:domain:manifest:transformation
:domain:manifest:validation \   one library JAR
:domain:datatable             ─►  (metricflow-core)
:domain:spec                  ─►  Kotlin packages
:domain:spec:bind             ─►  preserved verbatim
:domain:lookup
:domain:semantic-graph
:domain:query
:domain:dataflow
:domain:metric-evaluation
:domain:plan-conversion
:domain:sql:plan
:domain:sql:optimizer
:domain:sql:render
:domain:sqlclient (Step 3)
:infrastructure:sql:render:base (Step 4)
:infrastructure:sql:render:default (Step 4)
```
