# `:engine`

Clean in-process engine facade. It plans and renders SQL in memory, but owns no
transport, server bootstrap, logging implementation, or concrete dialect
renderer. Consumers explicitly register the renderer artifacts they serve using
`SqlPlanRendererRegistry`.

**Python sources**
- `metricflow/engine/metricflow_engine.py` (the `MetricFlowEngine` class)
- `metricflow/engine/models.py` (output DTOs: `Dimension`, `Entity`, `Metric`, `SavedQuery`)
- `metricflow/engine/time_source.py` (`ServerTimeSource`)
- `metricflow/execution/*` — **collapsed in Kotlin** to a `MetricFlowExplainRequest` data class
  per `docs/module-mapping.md`.

**Wave**: W10 (engine assembly), iterated by W11 (path-aware DFS resolver),
W12 (forward deps), W13 (15/23 visit methods filled), W14 (engine.explain
wired through the chain — body deferrals propagate to the call site).

## Layout

| File | Role |
|---|---|
| `MetricFlowEngine.kt` | The 8-entry-point facade. Mirrors Python `MetricFlowEngine` (minus SQL execution). |
| `EngineModels.kt` | Engine-facing `EngineDimension` / `EngineEntity` / `EngineMetric` / `EngineSavedQuery` DTOs. |
| `SqlPlanRendererRegistry.kt` | Explicit dialect-to-renderer composition seam. |
| `MetricFlowEngine.kt` | The 8-entry-point in-process facade. |
| `EngineModels.kt` | Engine-facing `EngineDimension` / `EngineEntity` / `EngineMetric` / `EngineSavedQuery` DTOs. |

The protobuf/gRPC service, wire adapters, and in-process transport helper live
in the optional `:grpc-server` module. Canonical JSON serialization for the
internal corpus runner is owned by `:internal-diff-runner`.

## RPC status (post-W11)

| RPC | Status | Notes |
|---|---|---|
| `RenderSql` (`explain`) | UNIMPLEMENTED (chain wired) | W14 routes the call through `MetricFlowQueryParser.parseAndValidateQuery` → `DataflowPlanBuilder.buildPlan` → `DataflowToSqlPlanConverter.convertToSqlPlan` → dialect renderer. The first deferred body in that chain (currently the parser's `parseAndValidateQuery` — it depends on `MetricFlowQueryResolver.resolve_query`, ~743 LOC) throws `NotImplementedError`, which propagates as `UNIMPLEMENTED`. |
| `ListMetrics` | Implemented | Diff-runner: 18/18 pass (post-W11). |
| `ListDimensions` | Implemented | Diff-runner: 19/19 pass. |
| `EntitiesForMetrics` | Implemented | Diff-runner: 3/3 pass. Origin model comes straight from the resolver's path-final `joined_model_ids[-1]`. |
| `ListGroupBys` | Implemented | Diff-runner: 5/5 pass. Combination of `listDimensions` + `entitiesForMetrics` per Python. |
| `ListSavedQueries` | Implemented | Iterates `manifest.saved_queries`. Diff-runner: 17/17 pass. |
| `ExplainGetDimensionValues` | UNIMPLEMENTED (chain wired) | Same dependency chain as `explain`. W14 also wired this through to delegate to `explain` via a synthetic group-by request. |
| `ValidateManifest` | Implemented | Delegates to `SemanticManifestValidator.withDefaultRules()`. Diff-runner: 19/19 pass. |

## Resolver behaviour (post-W11)

The semantic-graph attribute resolver is now the **path-aware DFS** variant
(see `:domain:semantic-graph` README "Resolver scope and deferred internals"
section). For each metric:

1. The resolver finds the metric's `SimpleMetricNode` / `ComplexMetricNode`.
2. It walks paths to every reachable attribute, accumulating an
   `AttributeRecipe` that tracks entity links, joined-model IDs, dunder-name
   parts, and properties.
3. Each attribute leaf emits a `DunderNameDescriptor` keyed by its
   indexed dunder-name. Multi-path ambiguity at the same dunder name is
   handled by the trie's `add_name_items` "ambiguity-erases" rule.
4. Per-metric tries are intersected at the `GroupByItemSet` level for
   multi-metric queries.

Practical consequences:

- Multi-hop dimensions (e.g. `listing__user__archived_at`) are surfaced with
  the correct `entity_links` chain and a canonical origin model
  (= the path-final semantic model).
- `entities_for_metrics` uses `originSemanticModelReferences.first()` to pick
  the canonical origin (matching Python's `mf_first_item`), and dedupes by
  `(entity_name, semantic_model_name)`. The W10 `preferredModels` heuristic is
  retained on the public method signature but is now a no-op.
- The "first-pass BFS" caveats from the prior README are resolved — the only
  remaining behavioural gap on the corpus is the explain path, which is out
  of W11 scope.

## Wire / hydration rules

1. **Manifest hydration**: `kotlinx-serialization` snake_case JSON via
   `ManifestJson`. `SemanticManifestTransformer.transform` is applied to every
   manifest (Python applies it pre-validation and pre-query) so the engine
   never sees pre-transform data.
2. **Bind parameters**: rendered exactly as `oracle.serialize.bind_parameter_set_to_dict`
   emits them (`{"param_items": [...]}`). The Kotlin port emits an empty
   object today because the explain path is still deferred.
3. **Validation issue JSON**: byte-equivalent to
   `oracle.serialize.issue_to_dict`. Used both on the wire (`validateManifest`
   proto response) and by the diff-runner for parity comparison.

## Error mapping

The gRPC service maps domain exceptions to canonical statuses:

- `NotImplementedError` → `UNIMPLEMENTED` (with the engine's own diagnostic).
- `SerializationException` / `IllegalArgumentException` → `INVALID_ARGUMENT`.
- Anything else → `INTERNAL`.

## What was rewritten in W10 (vs Phase 2 scaffolding)

- `MetricFlowSqlEngineService` — all eight RPCs now dispatch to the engine.
- `MetricFlowEngine` — new facade class. Wires `SemanticManifestLookup` +
  `SemanticManifestGraphLookup`.
- Two bug fixes landed in dependent modules:
  - `:common:toolkit` BFS pathfinder treated the `targetNodes` set as
    sinks (terminating expansion). The W10 `reachableFrom` helper now uses a
    dedicated dedup-BFS that never short-circuits.
  - `:domain:semantic_graph` resolver emitted the `LOCAL` property as a
    fallback for dimensions / entities to keep the simple-dimension filter
    from rejecting every attribute (the BFS pass didn't derive per-path
    properties). This fallback is no longer needed post-W11 — the DFS
    resolver derives `LOCAL` / `JOINED` / `MULTI_HOP` from the recipe's
    `joined_model_ids` count.

## What was wired in W14 (engine.explain chain)

The W14 wave did not port the algorithm bodies — they remain too large
(~5,000 combined Python LOC across the parser resolver, the dataflow plan
builder, and the 8 remaining visit methods). Instead, W14 wired the call
chain so the deferral surfaces correctly:

1. `MetricFlowEngine.explain` no longer throws upfront — it constructs a
   `MetricFlowQueryParser` and calls `parseAndValidateQuery`.
2. `MetricFlowQueryParser.parseAndValidateQuery` runs the partial pipeline
   (naming-scheme dispatch + resolution-DAG build) and then throws
   `NotImplementedError` because the resolver body is missing.
3. `CaseRunner` for the diff-runner now dispatches `explain` /
   `explain_get_dimension_values` cases to the engine (no more inline
   `throw NotImplementedError` shortcut).
4. The return type `MetricFlowExplainResult` is now defined so future
   waves can land the body without changing any signature.
5. The `MetricFlowSqlEngineService` gRPC methods produce a real proto
   `RenderSqlResponse` / `ExplainGetDimensionValuesResponse` from
   `MetricFlowExplainResult.sql` (currently unreachable — the engine
   throws first).

Diff-runner result: 81 PASS / 31 UNIMPLEMENTED / 0 FAIL / 0 ERROR
(unchanged from W13). The next wave should port either:

- The query resolver (~743 LOC) so the parser stops being the first
  thrower, OR
- The dataflow plan builder body (~2,500 LOC) — useful in isolation
  with a hand-crafted `MetricFlowQuerySpec`.

## What was rewritten in W11 (DFS resolver)

- Replaced the BFS body in
  `:domain:semantic-graph/SemanticGraphGroupByItemSetResolver` with a DFS
  port (see that module's README for details). Resolver output is now
  Python-equivalent for `LinkableElementType.{DIMENSION, TIME_DIMENSION,
  ENTITY}` on every corpus case.
- Simplified `MetricFlowEngine.filterLinkableEntities` to consume
  `annotated.originSemanticModelReferences.first()` directly — the
  `preferredModels` heuristic from W10 is now a no-op.
- Simplified `MetricFlowEngine.createDimensionsFromSpec` to rely on the
  resolver's already-correct `entity_links` and origin model list (no
  primary-entity fallback needed).
