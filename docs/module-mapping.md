# Phase 0 — Python module → Kotlin package mapping

For every Python sub-package in the **reachable** set, the planned Kotlin package under `cc.monomer.metricflow.*`.

## Naming policy (recap from CLAUDE.md "이름 보존")

- Preserve metricflow's domain vocabulary verbatim. `linkable_spec` → `LinkableSpec`, never "QualifiedSpec" or "JoinableSpec".
- Package layout follows the **semantic flow** (manifest → spec → dataflow → SQL plan → SQL render), not Python's alphabetical accident.
- `domain.*` is pure (no kotlinx-coroutines, no I/O, no Spring).
- `application.*` orchestrates use cases.
- `infrastructure.*` holds dialect-specific renderers (the only thing that really varies by environment in this engine).

## Mapping table (Python sub-package → Kotlin package)

Order is the porting order ("semantic flow downstream"). Entries with the same Kotlin package land in the same Kotlin module but in distinct sub-packages or files.

### Foundation: manifest model

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow_semantic_interfaces` (root: `references`, `enum_extension`, `errors`, `pretty_print`, `dataclass_serialization`, `call_parameter_sets`) | `cc.monomer.metricflow.domain.manifest.model` | `references.py` defines `MetricReference`, `EntityReference`, `DimensionReference`, etc. → all `@JvmInline value class`. |
| `metricflow_semantic_interfaces/type_enums` | `cc.monomer.metricflow.domain.manifest.model.enums` | One Kotlin enum per file: `AggregationType`, `MetricType`, `TimeGranularity`, `DimensionType`, `EntityType`, `DatePart`, `PeriodAggregation`, `ConversionCalculationType`, `ExportDestinationType`, `SemanticManifestNodeType`. |
| `metricflow_semantic_interfaces/protocols` | `cc.monomer.metricflow.domain.manifest.model` (interfaces alongside their implementations) | One Kotlin `interface` per Python `Protocol`. KDoc references the implementation class. |
| `metricflow_semantic_interfaces/implementations` (top-level files) | `cc.monomer.metricflow.domain.manifest.model` | `PydanticMetric` → `Metric` (data class). The "Pydantic" prefix is dropped — it was a Python framework artifact. |
| `metricflow_semantic_interfaces/implementations/elements` | `cc.monomer.metricflow.domain.manifest.model.element` | `Dimension`, `Entity`, `Measure` data classes. |
| `metricflow_semantic_interfaces/implementations/filters` | `cc.monomer.metricflow.domain.manifest.model.filter` | `WhereFilter`, `WhereFilterIntersection`. |
| `metricflow_semantic_interfaces/naming` (`dundered`, `keywords`) | `cc.monomer.metricflow.domain.manifest.model.naming` | `DUNDER` constant, `METRIC_TIME_ELEMENT_NAME`, etc. |
| `metricflow_semantic_interfaces/parsing/text_input/*` | `cc.monomer.metricflow.domain.manifest.model.text` | Text-template processor (Jinja-shaped). Only the in-scope subset. |
| `metricflow_semantic_interfaces/parsing/where_filter/{jinja_object_parser,parameter_set_factory}.py` | `cc.monomer.metricflow.domain.manifest.model.filter.parser` | Where-filter parsers. |
| `metricflow_semantic_interfaces/parsing/yaml_loader.py` | `cc.monomer.metricflow.domain.manifest.model.io` | Generic YAML loader, kept because where-filter tests need it; not on the production path (we hydrate from JSON via kotlinx-serialization). |

### Manifest pipelines: transformations + validations

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow_semantic_interfaces/transformations` (`semantic_manifest_transformer`, `pydantic_rule_set`, `transform_rule`, `rule_set`) | `cc.monomer.metricflow.domain.manifest.transformation` | Rule-pipeline orchestration. |
| `metricflow_semantic_interfaces/transformations/{add_input_metric_measures, boolean_aggregations, boolean_measure, convert_count, convert_median, cumulative_type_params, fix_proxy_metrics, flatten_simple_metrics_with_measure_inputs, names, proxy_measure, remove_plural_from_window_granularity, replace_input_measures_with_simple_metrics_transformation}.py` | `cc.monomer.metricflow.domain.manifest.transformation.rule` | One file per transform rule. |
| `metricflow_semantic_interfaces/transformations/measure_to_metric_transformation_pieces/measure_features_to_metric_name.py` | `cc.monomer.metricflow.domain.manifest.transformation.rule.measure_to_metric` | Transform helper. Single-file Python sub-package. |
| `metricflow_semantic_interfaces/validations/semantic_manifest_validator.py` | `cc.monomer.metricflow.domain.manifest.validation` | The validator class + `DEFAULT_RULES` list. |
| `metricflow_semantic_interfaces/validations/validator_helpers.py` | `cc.monomer.metricflow.domain.manifest.validation` | `ValidationIssue` family + `validate_safely` decorator. Issue → `sealed interface` (see `data-model-mapping.md`). |
| `metricflow_semantic_interfaces/validations/{agg_time_dimension, dimension_const, element_const, entities, labels, measures, metrics, non_empty, primary_entity, reserved_keywords, saved_query, semantic_models, time_dimension_has_granularity, time_spines, unique_valid_name, where_filters, shared_measure_and_metric_helpers}.py` | `cc.monomer.metricflow.domain.manifest.validation.rule` | One file per rule (or rule cluster). 16 rule-bearing files; 27 rule classes total. See `validation-rules-inventory.md`. |

### Common utilities

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow_semantics/toolkit` (root: `assert_one_arg`, `comparison_helpers`, `dataclass_helpers`, `id_helpers`, `merger`, `mf_type_aliases`, `orderd_enum`, `performance_helpers`, `singleton`, `string_helpers`, `syntactic_sugar`, `table_helpers`, `time_helpers`, `visitor`) | `cc.monomer.metricflow.common.util` | `mf_random_id`, `mf_first_item`, `assert_one_arg` etc. Most map to top-level extension functions; some Kotlin equivalents (`Comparable`, `OrderedEnum`) are stdlib already and disappear. |
| `metricflow_semantics/toolkit/cache` | `cc.monomer.metricflow.common.util.cache` | LRU + result memoisation. |
| `metricflow_semantics/toolkit/collections` | `cc.monomer.metricflow.common.util.collections` | Ordered set, mapping helpers. |
| `metricflow_semantics/toolkit/mf_graph` (root + `formatting/{dot_attributes, graph_formatter, pretty_graph_formatter}` + `path_finding/*`) | `cc.monomer.metricflow.common.graph` | Generic DAG framework metricflow built itself. |
| `metricflow_semantics/toolkit/mf_logging` | `cc.monomer.metricflow.common.logging` | `LazyFormat`, `pretty_formatter`, runtime block timing. Kotlin gets `Logger.lazy { … }` extensions. |
| `metricflow_semantics/dag` (`mf_dag`, `id_prefix`, `dag_to_text`, `sequential_id`) | `cc.monomer.metricflow.common.dag` | The DAG node base + `DagId`/`NodeId` types. Used everywhere downstream. |
| `metricflow_semantics/errors` (`error_classes`, `custom_grain_not_supported`) | `cc.monomer.metricflow.common.errors` (under `:common:toolkit`) | Domain exception classes. |
| `metricflow_semantics/time` | `cc.monomer.metricflow.common.time` | `ExpandedTimeGranularity`, `dateutil_adjuster`, `time_constants`, `time_period`, `time_spine_source`, `time_source`. |
| `metricflow_semantics/filters/time_constraint.py` | `cc.monomer.metricflow.common.time` | `TimeRangeConstraint` lives here in Python; logically time. |
| `metricflow/telemetry/*` | `cc.monomer.metricflow.common.telemetry` | `TelemetryReporter`, `TelemetryLevel`, log-call decorator. Kotlin uses Micrometer or just KLogger; this module stays small. |
| `metricflow/data_table/*` | `cc.monomer.metricflow.domain.datatable` | `MetricFlowDataTable`, `MfColumn`, `column_types`. Used as a parameter type by `SqlClient` even though we don't execute. |

### Spec & SQL layer (independent of dataflow)

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow_semantics/sql` (`sql_bind_parameters`, `sql_table`, `sql_exprs`, `sql_column_type`, `sql_join_type`) | `cc.monomer.metricflow.domain.spec.bind` | `SqlBindParameterSet`, `SqlTable`, `SqlExpression` family. The `SqlExpression` type is a sealed hierarchy with ~20 cases; large file in Python (`sql_exprs.py`, ~1.6k LOC) splits into one Kotlin file per variant. |
| `metricflow_semantics/specs` (root files) | `cc.monomer.metricflow.domain.spec` | `InstanceSpec`, `DimensionSpec`, `EntitySpec`, `MetricSpec`, `TimeDimensionSpec`, `LinkableSpecSet`, `MetricFlowQuerySpec`, `OrderBySpec`, `ColumnAssociation`, etc. All sealed interface families. |
| `metricflow_semantics/specs/patterns` | `cc.monomer.metricflow.domain.spec.pattern` | `SpecPattern` family for matching specs (used by query parsing and dataflow planning). |
| `metricflow_semantics/specs/where_filter` (`where_filter_spec`, `where_filter_spec_factory`, `where_filter_metric`) | `cc.monomer.metricflow.domain.spec.where` | `WhereFilterSpec` and factory. Note: `where_filter_dimension/entity/time_dimension` are reachable here (they're query-side filters, distinct from the manifest-side parsers above). |
| `metricflow_semantics/naming` (`dunder_scheme`, `linkable_spec_name`, `metric_scheme`, `naming_scheme`, `object_builder_scheme`, `object_builder_str`) | `cc.monomer.metricflow.domain.spec.naming` | Dunder name encoding, naming schemes. `StructuredLinkableSpecName` is a key type used by the engine and the wrapper. |
| `metricflow_semantics/aggregation_properties.py`, `metricflow_semantics/instances.py` | `cc.monomer.metricflow.domain.spec` | Root-level files belong logically with specs. |
| `metricflow/sql` (top-level: `sql_plan`, `sql_select_node`, `sql_ctas_node`, `sql_cte_node`, `sql_select_text_node`, `sql_table_node`, `column_alias_renamer`) | `cc.monomer.metricflow.domain.sql.plan` | The SQL plan node hierarchy (sealed). |
| `metricflow/sql/optimizer` (`optimization_levels`, `rewriting_sub_query_reducer`, `sql_query_plan_optimizer`, `table_alias_simplifier`) | `cc.monomer.metricflow.domain.sql.optimizer` | SQL plan rewrites. |
| `metricflow/sql/optimizer/column_pruning` | `cc.monomer.metricflow.domain.sql.optimizer.column_pruning` | Column-pruning sub-pass. |
| `metricflow/sql/render/sql_plan_renderer.py` (interface part) | `cc.monomer.metricflow.domain.sql.render` | `SqlPlanRenderer` *interface* + `SqlPlanRenderResult`. (The `DefaultSqlPlanRenderer` implementation lives below.) Splits the Python file: in Kotlin we keep the port layer pure. |
| `metricflow/sql/render/sql_plan_renderer.py` (default implementation), `expr_renderer.py`, `rendering_constants.py` | `cc.monomer.metricflow.infrastructure.sql.render` | The base/default implementation. |
| `metricflow/sql/render/{trino, big_query, snowflake, databricks, redshift, duckdb_renderer, postgres}.py` | `cc.monomer.metricflow.infrastructure.sql.render.<dialect>` | One sub-package per dialect: `trino`, `bigquery`, `snowflake`, `databricks`, `redshift`, `duckdb`, `postgres`. Each is small (79–207 LOC) and is its own Gradle module per FEASIBILITY §5. |

### Lookup, semantic graph, query

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow_semantics/model` (root: `linkable_element_property`, `semantic_manifest_lookup`, `semantic_model_derivation`) | `cc.monomer.metricflow.domain.lookup` | Top-level lookup helpers. |
| `metricflow_semantics/model/semantics` (`semantic_model_lookup`, `metric_lookup`, `dimension_lookup`, `linkable_element`, `linkable_element_set_base`, `linkable_spec_resolver`, `semantic_model_helper`, `semantic_model_join_evaluator`, `simple_metric_input`, `element_filter`) | `cc.monomer.metricflow.domain.lookup.semantics` | The big lookup classes. |
| `metricflow/validation/dataflow_join_validator.py` | `cc.monomer.metricflow.domain.lookup` | Sole reachable file from `metricflow/validation/`; semantically a join-time check. |
| `metricflow_semantics/semantic_graph` (root: `model_id`, `sg_constant`, `sg_exceptions`, `sg_interfaces`, `sg_node_grouping`) | `cc.monomer.metricflow.domain.semantic_graph` | Semantic graph base types. |
| `metricflow_semantics/semantic_graph/{nodes,edges}` | `cc.monomer.metricflow.domain.semantic_graph.{node,edge}` | Sealed-interface families for semantic-graph nodes and edges. Both have 5+ variants → own subpackages. |
| `metricflow_semantics/semantic_graph/lookups` | `cc.monomer.metricflow.domain.semantic_graph.lookup` | `entity_lookup`, `join_lookup`, `manifest_object_lookup`, `model_object_lookup`, `simple_metric_model_object_lookup`. |
| `metricflow_semantics/semantic_graph/builder` | `cc.monomer.metricflow.domain.semantic_graph.builder` | Subgraph generators (categorical, entity, time, metric, simple_metric). |
| `metricflow_semantics/semantic_graph/attribute_resolution` | `cc.monomer.metricflow.domain.semantic_graph.attribute` | `AttributeRecipe`, `RecipeWriterPath`, `GroupByItemSet`, `SgLinkableSpecResolver`. |
| `metricflow_semantics/semantic_graph/trie_resolver` | `cc.monomer.metricflow.domain.semantic_graph.trie` | `DunderNameTrie`, resolvers. |
| `metricflow_semantics/protocols/query_parameter.py` | `cc.monomer.metricflow.domain.query` | `MetricQueryParameter`, `GroupByQueryParameter`, etc. — query API surface. |
| `metricflow_semantics/query` (root: `query_parser`, `query_resolver`, `query_resolution`, `order_by_helper`, `similarity`, `suggestion_generator`) | `cc.monomer.metricflow.domain.query` | Top-level query parser/resolver. |
| `metricflow_semantics/query/group_by_item` (root + `candidate_push_down`, `filter_spec_resolution`, `resolution_dag`, `resolution_dag/resolution_nodes`) | `cc.monomer.metricflow.domain.query.group_by` (with sub-packages `candidate_pushdown`, `filter_spec`, `resolution_dag`, `resolution_dag.node`) | Group-by item resolution machinery. |
| `metricflow_semantics/query/issues` (+ `filter_spec_resolver`, `group_by_item_resolver`, `parsing` sub-folders) | `cc.monomer.metricflow.domain.query.issue` | `MetricFlowQueryResolutionIssue` and concrete issue classes. Sealed. |
| `metricflow_semantics/query/resolver_inputs` | `cc.monomer.metricflow.domain.query.input` | Resolver input ADTs. |
| `metricflow_semantics/query/validation_rules` | `cc.monomer.metricflow.domain.query.validation` | Query-level rules (e.g. `metric_time_requirements`, `unique_column_names`). Distinct from manifest-validation rules. |

### Dataflow, plan conversion, SQL client

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow/dataflow` (root: `dataflow_plan`, `dataflow_plan_visitor`, `dataflow_plan_analyzer`) | `cc.monomer.metricflow.domain.dataflow` | Dataflow plan + visitor base. |
| `metricflow/dataflow/builder` | `cc.monomer.metricflow.domain.dataflow.builder` | `DataflowPlanBuilder`, source-node assembly. |
| `metricflow/dataflow/nodes` | `cc.monomer.metricflow.domain.dataflow.node` | 22 node types. Sealed; sub-package because 5+ variants. |
| `metricflow/dataflow/optimizer` | `cc.monomer.metricflow.domain.dataflow.optimizer` | Optimizer factory + base. |
| `metricflow/dataflow/optimizer/source_scan` | `cc.monomer.metricflow.domain.dataflow.optimizer.source_scan` | Source-scan-specific optimization. |
| `metricflow/dataset` | `cc.monomer.metricflow.domain.dataflow.dataset` | `SemanticModelDataSet`, `convert_semantic_model`, `dataset_classes`, `sql_dataset`. Folded under `dataflow` because that's the only consumer. |
| `metricflow/metric_evaluation` (root + `passthrough` + `plan` sub-folders) | `cc.monomer.metricflow.domain.dataflow.metric_evaluation` | Metric evaluation planning (passthrough optimization). Closely tied to dataflow. |
| `metricflow/plan_conversion` (root: `convert_to_sql_plan`, `node_processor`, `select_column_gen`, `spec_transforms`, `sql_expression_builders`) | `cc.monomer.metricflow.domain.plan_conversion` | Dataflow → SQL plan converter base. |
| `metricflow/plan_conversion/instance_set_transforms` | `cc.monomer.metricflow.domain.plan_conversion.instance_set` | Spec-set transforms applied during conversion. |
| `metricflow/plan_conversion/to_sql_plan` (`dataflow_to_sql`, `dataflow_to_subquery`, `dataflow_to_cte`, `output_column_orderer`, `sql_join_builder`) | `cc.monomer.metricflow.domain.plan_conversion.to_sql` | The big visitor that walks dataflow nodes and emits SQL plan. |
| `metricflow/protocols/sql_client.py` | `cc.monomer.metricflow.domain.sqlclient` | The `SqlClient` port. We keep `sqlEngineType`, `renderBindParameterKey`, `sqlPlanRenderer` only (no `query`/`execute`/`dryRun`). |

### Engine facade

| Python module | Kotlin package | Notes |
|---|---|---|
| `metricflow/engine/metricflow_engine.py` | `cc.monomer.metricflow.application.engine` | The entry-point class with the 7 SQL-generation methods. |
| `metricflow/engine/models.py` | `cc.monomer.metricflow.application.engine` | `Dimension`, `Entity`, `Metric`, `SavedQuery`, `SearchableElement` — the engine's *output* DTOs (distinct from the manifest's input types). |
| `metricflow/engine/time_source.py` | `cc.monomer.metricflow.application.engine` | `ServerTimeSource`. |
| `metricflow/execution/{__init__, convert_to_execution_plan, dataflow_to_execution, execution_plan}.py` | `cc.monomer.metricflow.application.engine.result` | **Collapsed** in Kotlin to one small file: `MetricFlowExplainResult { querySpec, dataflowPlan, sqlPlan, sql, bindParameterSet }`. The Python visitor pattern around `WriteToResultDataTableNode`/`WriteToResultTableNode` becomes a single-call helper. |

## Note on the `Pydantic` prefix

Almost every implementation class in `metricflow_semantic_interfaces/implementations/` is named `PydanticX` (e.g. `PydanticMetric`). This is a Python framework artifact — Pydantic is the validation library; `Pydantic` is not part of the domain language. **In Kotlin we drop the `Pydantic` prefix** and use the protocol name directly: `Pydantic Metric` → `Metric`, `PydanticDimension` → `Dimension`, etc. The `protocols/` interfaces and `implementations/` data classes thus collapse into one Kotlin file per concept (data class is the structure, the interface is implicit because Kotlin doesn't need a separate Protocol layer when the structure is fixed).

For types where the engine has both an *input* `Dimension` (in the manifest) and an *output* `Dimension` (in `engine/models.py`), we disambiguate with package: `domain.manifest.model.element.Dimension` vs `application.engine.Dimension`. The Python codebase already does this implicitly (different files); we make it explicit through Kotlin's package system.

## What is NOT mapped

Everything in `unreachable.txt` (97 files / 7,231 LOC) — see `scope.md`. Notably:

- `metricflow/converters/` (OSI ↔ MSI) — no Kotlin counterpart.
- `metricflow_semantic_interfaces/parsing/{schemas, dir_to_model, ...}` — YAML hydration; we do JSON via kotlinx-serialization.
- `metricflow_semantic_interfaces/test_utils.py` — test-only.
- `metricflow_semantics/test_helpers/` — test-only.
- `metricflow/sql/render/__init__.py` — empty; no Kotlin file needed.
