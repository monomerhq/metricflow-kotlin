# Public API Surface (metricflow-core JAR)

This document defines the **stable public API** that consumers of
`metricflow-kotlin` may import. Anything not listed here is considered
implementation detail and may be `internal` (in a future Phase 5 sweep) or
otherwise breakable between versions.

The Kotlin package names below preserve Python source traceability — see
`CLAUDE.md` "이름 보존" rule.

---

## 1. Engine facade (`cc.monomer.metricflow.application.engine`)

This is the **entry point** consumers will use 95% of the time. Port of
`metricflow/engine/metricflow_engine.py`.

### Class
- `MetricFlowEngine(semanticManifest: SemanticManifest)`

### Methods
- `validateManifest(): SemanticManifestValidationResults`
- `listMetrics(includeDimensions: Boolean): List<EngineMetric>`
- `listDimensions(metricNames: List<String>?, orderBy: GroupByOrderByAttribute): List<EngineDimension>`
- `entitiesForMetrics(metricNames: List<String>): List<EngineEntity>`
- `listGroupBys(metricNames: List<String>?, includeDerivedTimeGranularities: Boolean, orderBy: GroupByOrderByAttribute): GroupByListing`
- `listSavedQueries(): List<EngineSavedQuery>`
- `explain(request: MetricFlowExplainRequest): MetricFlowExplainResult`
- `explainGetDimensionValues(request: ExplainGetDimensionValuesRequest): MetricFlowExplainResult`

### Request/result types
- `MetricFlowExplainRequest`
- `MetricFlowExplainResult`
- `ExplainGetDimensionValuesRequest`
- `GroupByListing`
- `GroupByOrderByAttribute` (enum)

### Engine-facing DTOs (`EngineModels.kt`)
- `EngineMetric`, `EngineDimension`, `EngineEntity`, `EngineSavedQuery`
- `EngineSavedQueryQueryParams`, `EngineExport`
- `SearchableElement` (marker interface)

---

## 2. Semantic Manifest data model
(`cc.monomer.metricflow.domain.manifest.model`)

The data layer consumers fill out (or deserialize from YAML) to describe their
warehouse. Port of `metricflow_semantic_interfaces/implementations/*`.

### Top-level
- `SemanticManifest`
- `SemanticManifestNodeRelation`

### Element types (`...manifest.model.element`)
- `Dimension`, `Entity`, `Measure`, `MetricInputMeasure`
- `DimensionTypeParams`, `DimensionValidityParams`
- `Metric`, `MetricTypeParams`, `MetricInput`
- `SemanticModel`, `NodeRelation`

### Saved queries
- `SavedQuery`, `SavedQueryQueryParams`, `Export`

### Filters
- `WhereFilter`, `WhereFilterIntersection`

### Enums (`...manifest.model.enums`)
- `DimensionType`, `EntityType`, `MetricType`
- `AggregationType`, `TimeGranularity`, `DatePart`
- `PeriodAggregation`, `ConversionMetricType`, etc.

### References (`...manifest.model.references`)
- `MetricReference`, `DimensionReference`, `EntityReference`
- `MeasureReference`, `SemanticModelReference`, `SemanticModelElementReference`
- `TimeDimensionReference`

### Validation
- `SemanticManifestValidator` (`cc.monomer.metricflow.domain.manifest.validation`)
- `SemanticManifestValidationResults`
- `ValidationIssue`, `ValidationIssueLevel`

---

## 3. Query specs (`cc.monomer.metricflow.domain.spec`)

Returned in `MetricFlowExplainResult.querySpec`. Consumers may inspect — they
generally do not construct.

- `MetricFlowQuerySpec` (root)
- `LinkableInstanceSpec` (sealed interface for group-by items)
- `DimensionSpec`, `TimeDimensionSpec`, `EntitySpec`
- `MetricSpec`, `GroupByMetricSpec`
- `OrderBySpec`, `InstanceSpecSet`

### Bind layer (`...spec.bind`)
- `SqlTable` (returned in `MetricFlowExplainResult.outputSqlTable`)

---

## 4. SQL render interfaces (`cc.monomer.metricflow.domain.sql.render`)

These are the **renderer interfaces** that dialect modules (`metricflow-render-trino`,
etc.) implement. Consumers may write their own dialect if needed.

- `SqlPlanRenderer` (interface)
- `SqlExpressionRenderer` (interface)
- `SqlRenderingEngine` (interface — dialect-feature metadata)
- `SqlRenderingConstants`
- `SqlEngine` (enum — moved from `:domain:sqlclient` in Phase 5 Step 3)
- `SqlClient` (interface — render-side capabilities only, no execution methods)

---

## 5. Time/common (`cc.monomer.metricflow.common.time`)

- `TimeRangeConstraint` (used in `MetricFlowExplainRequest.timeConstraint{Start,End}`)

---

## NOT public (implementation details)

The following are explicitly **not** part of the stable API and may be marked
`internal` in future sweeps, refactored, or removed. Consumers depending on
them risk breakage:

- `cc.monomer.metricflow.domain.dataflow.builder.*` — dataflow plan construction
- `cc.monomer.metricflow.domain.dataflow.builder.dataset.*`
- `cc.monomer.metricflow.domain.plan_conversion.helpers.*` — visit/lookup helpers
- `cc.monomer.metricflow.domain.plan_conversion.NodeProcessor*` — node walk
- `cc.monomer.metricflow.domain.lookup.*Helper` — internal lookups (except SemanticManifestLookup which is needed by query parser)
- `cc.monomer.metricflow.domain.metric_evaluation.*` — recursion strategies
- `cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.*` — BFS/DFS resolver internals
- `cc.monomer.metricflow.domain.query.MetricFlowQueryParser` internals (the parse method is invoked by `MetricFlowEngine.explain`; callers should not invoke it directly)
- `cc.monomer.metricflow.domain.sql.optimizer.*` — column pruner, predicate pushdown, etc.
- All `Visitor`/`*Visitor` classes (visitor pattern internals)
- All `*Renderer` implementations in `cc.monomer.metricflow.infrastructure.sql.render.*` — call `SqlPlanRenderer` via the engine
- All `application.engine.adapter.*` — proto/JSON adapters (gRPC-server module only)
- `application.engine.MetricFlowSqlEngineService` — gRPC service impl (gRPC-server module only)
- `application.engine.MetricFlowGrpcServer*` / `InProcessGrpc` — server bootstrap (gRPC-server module only)
- `common.toolkit.*` — internal helpers (string utils, etc.)

---

## Stability tier

| Tier | What it means |
|---|---|
| **Stable** (above) | Will not break between minor versions |
| **Evolving** | Listed above but may grow new optional methods; existing calls remain compatible |
| **Internal** (the "NOT public" list) | May change without notice |

As of Phase 5 (Step 1), no API is yet `@Stable`-annotated. The convention is
**presence in this document = stable**.

---

## What changed in Phase 5

- Step 3: `cc.monomer.metricflow.domain.sqlclient.{SqlClient, SqlEngine}` were moved into `cc.monomer.metricflow.domain.sql.render.*` (package name change). The 113 LOC `:domain:sqlclient` Gradle module was absorbed into `:domain:sql:render`.
- Step 4: `DialectSqlRenderingEngine` (from `:infrastructure:sql:render:base`) and `DefaultDialectSqlPlanRenderer` (from `:infrastructure:sql:render:default`) were absorbed into `:domain:sql:render`. Their Kotlin package names were preserved.
