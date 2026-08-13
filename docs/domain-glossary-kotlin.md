# Phase 0 — Domain glossary (Kotlin)

metricflow's core domain vocabulary, with the Kotlin name we'll use. Anyone porting a new module reads this first.

> Source note: the upstream `python_oracle/upstream/GLOSSARY.md` only defines persistence-source terminology (~13 lines) and contains none of the engine-domain terms below. This glossary is therefore derived from the codebase itself (file paths cited per term).

## Naming policy reaffirmed

CLAUDE.md "이름 보존" rule applies. **Do not rename a metricflow term to something more "natural" in English or more "idiomatic" in Kotlin.** The list below is the contract.

- `metric_time` → `MetricTime` (a dimension; not "EventTime", not "QueryTime").
- `linkable_spec` → `LinkableSpec`. Not "JoinableSpec", not "QualifiedSpec".
- `dunder_name` → `DunderName`. Not "QualifiedName", not "DottedName".
- `semantic_manifest` → `SemanticManifest`. Not "MetricCatalog", not "Schema".
- `dataflow_plan` → `DataflowPlan`. Not "QueryPlan", not "ExecutionPlan" (which is a different thing).
- `time_spine` → `TimeSpine`. Not "DateAxis", not "TimeRange".

PascalCase from snake_case is the only mechanical change. The single exception is the `Pydantic` prefix on implementation classes (`PydanticMetric` → `Metric`) — that prefix is a Python framework artifact, not domain language.

`Pydantic` prefix removal exception list: every class under `metricflow_semantic_interfaces/implementations/` whose name starts with `Pydantic` drops the prefix in Kotlin. The corresponding `Protocol` class (under `protocols/`) is dropped entirely (the data class structure is the interface). When a name collision would occur (e.g. `domain.manifest.model.Dimension` vs `application.engine.Dimension`), they live in different Kotlin packages — both keep their bare name.

## Glossary

Source notes: where the term is defined in upstream code, the **File** column points to the file. The **Kind** column tells you what *kind* of Kotlin type to expect. "value class" means single-field, immutable, ID-shaped. "data class" means a Pydantic-style record. "sealed interface" means a closed sum type with a fixed set of variants. "interface" means an open contract.

### Top-level

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `semantic_manifest` | `SemanticManifest` | data class | `metricflow_semantic_interfaces/implementations/semantic_manifest.py` | Root container holding `semanticModels`, `metrics`, `projectConfiguration`, `savedQueries`, `timeSpines`. |
| `semantic_model` | `SemanticModel` | data class | `metricflow_semantic_interfaces/implementations/semantic_model.py` | A single source-of-truth dataset with measures + dimensions + entities. |
| `metric` | `Metric` | data class | `implementations/metric.py` | A `Metric` (Pydantic-prefix dropped). The manifest-side type, not the engine's output `Metric` (see below). |
| `measure` | `Measure` | data class | `implementations/elements/measure.py` | An aggregatable column declaration. |
| `dimension` | `Dimension` | data class | `implementations/elements/dimension.py` | A grouping column declaration. |
| `entity` | `Entity` | data class | `implementations/elements/entity.py` | A join key declaration. |
| `saved_query` | `SavedQuery` | data class | `implementations/saved_query.py` | A pre-named query (metrics + group-bys + order/limit/where). |
| `where_filter` | `WhereFilter` | data class | `implementations/filters/where_filter.py` | A templated SQL where expression with placeholders for `Dimension('...')`, `Entity('...')`, etc. |
| `where_filter_intersection` | `WhereFilterIntersection` | data class | (same file) | A list of `WhereFilter`s to AND together. |
| `time_spine` | `TimeSpine` | data class | `implementations/time_spine.py` | A table emitting a continuous date series at some granularity. Used for time-based joins. |
| `metric_time` | `MetricTime` | constant + concept | (string `METRIC_TIME_ELEMENT_NAME` in `metricflow_semantic_interfaces/naming/keywords.py`) | The built-in time dimension MetricFlow exposes for every metric. Not a Kotlin type per se; it's the literal string name `"metric_time"` that the engine reserves. |

### Reference types (value classes)

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `element_reference` | `ElementReference` | sealed interface | `metricflow_semantic_interfaces/references.py` | Common interface for all named-element references. |
| `linkable_element_reference` | `LinkableElementReference` | sealed interface | (same) | Element reference that participates in joins (entity/dimension). |
| `metric_reference` | `MetricReference` | value class | (same) | `@JvmInline value class MetricReference(val elementName: String)`. |
| `measure_reference` | `MeasureReference` | value class | (same) | |
| `dimension_reference` | `DimensionReference` | value class | (same) | |
| `time_dimension_reference` | `TimeDimensionReference` | value class | (same) | A dimension whose type is TIME. |
| `entity_reference` | `EntityReference` | value class | (same) | |
| `semantic_model_reference` | `SemanticModelReference` | value class | (same) | Wraps a single `semanticModelName`. |
| `semantic_model_element_reference` | `SemanticModelElementReference` | data class | (same) | Two-field: `semanticModelName + elementName`. |
| `group_by_metric_reference` | `GroupByMetricReference` | value class | (same) | Metric used as a group-by. |

### Enums

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `metric_type` | `MetricType` | enum class | `type_enums/metric_type.py` | `SIMPLE`, `RATIO`, `CUMULATIVE`, `DERIVED`, `CONVERSION`. |
| `aggregation_type` | `AggregationType` | enum class | `type_enums/aggregation_type.py` | SUM, COUNT, AVERAGE, MIN, MAX, … |
| `dimension_type` | `DimensionType` | enum class | `type_enums/dimension_type.py` | `CATEGORICAL`, `TIME`. |
| `entity_type` | `EntityType` | enum class | `type_enums/entity_type.py` | `PRIMARY`, `UNIQUE`, `FOREIGN`, `NATURAL`. |
| `time_granularity` | `TimeGranularity` | enum class | `type_enums/time_granularity.py` | `NANOSECOND` … `YEAR`. |
| `date_part` | `DatePart` | enum class | `type_enums/date_part.py` | `DAY_OF_WEEK`, `DAY_OF_MONTH`, etc. |
| `period_aggregation` | `PeriodAggregation` | enum class | `type_enums/period_agg.py` | `FIRST`, `LAST`, `AVERAGE`. |
| `conversion_calculation_type` | `ConversionCalculationType` | enum class | `type_enums/conversion_calculation_type.py` | |
| `export_destination_type` | `ExportDestinationType` | enum class | `type_enums/export_destination_type.py` | |
| `semantic_manifest_node_type` | `SemanticManifestNodeType` | enum class | `type_enums/semantic_manifest_node_type.py` | |

### Specs (the engine's internal IR for "what we want")

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `instance_spec` | `InstanceSpec` | sealed interface | `metricflow_semantics/specs/instance_spec.py` | Base for all specs. |
| `linkable_instance_spec` | `LinkableInstanceSpec` | sealed interface | (same) | Specs that can carry `entity_links`. |
| `dimension_spec` | `DimensionSpec` | data class | `specs/dimension_spec.py` | Implements `LinkableInstanceSpec`. |
| `entity_spec` | `EntitySpec` | data class | `specs/entity_spec.py` | |
| `time_dimension_spec` | `TimeDimensionSpec` | data class | `specs/time_dimension_spec.py` | Extends `DimensionSpec` with `timeGranularity` + `datePart`. |
| `metric_spec` | `MetricSpec` | data class | `specs/metric_spec.py` | |
| `simple_metric_input_spec` | `SimpleMetricInputSpec` | data class | `specs/simple_metric_input_spec.py` | |
| `group_by_metric_spec` | `GroupByMetricSpec` | data class | `specs/group_by_metric_spec.py` | |
| `metadata_spec` | `MetadataSpec` | data class | `specs/metadata_spec.py` | |
| `instance_spec_set` | `InstanceSpecSet` | data class | `specs/spec_set.py` | The bag-of-specs container; central data structure. |
| `linkable_spec_set` | `LinkableSpecSet` | data class | `specs/linkable_spec_set.py` | Specifically dimensions + entities + group-by-metrics. |
| `metricflow_query_spec` | `MetricFlowQuerySpec` | data class | `specs/query_spec.py` | The fully-resolved query (post-parsing). |
| `where_filter_spec` | `WhereFilterSpec` | data class | `specs/where_filter/where_filter_spec.py` | Resolved filter (vs the raw `WhereFilter`). |
| `order_by_spec` | `OrderBySpec` | data class | `specs/order_by_spec.py` | |
| `partition_spec_set` | `PartitionSpecSet` | data class | `specs/partition_spec_set.py` | |
| `time_window` | `TimeWindow` | data class | `specs/time_window.py` | Count + granularity. |
| `column_association` | `ColumnAssociation` | data class | `specs/column_assoc.py` | spec ↔ rendered column-name binding. |
| `column_correlation_key` | `ColumnCorrelationKey` | sealed interface | `specs/column_assoc.py` | |
| `non_additive_dimension_spec` | `NonAdditiveDimensionSpec` | data class | `specs/non_additive_dimension_spec.py` | |
| `constant_property_spec` | `ConstantPropertySpec` | data class | `specs/constant_property_spec.py` | |
| `spec_pattern` | `SpecPattern` | sealed interface | `specs/patterns/spec_pattern.py` | Pattern for matching specs (used by the parser). |
| `entity_link_pattern` | `EntityLinkPattern` | data class | `specs/patterns/entity_link_pattern.py` | |

### Naming + dunder

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `dunder_name` | `DunderName` | value class (or pure helper functions) | `metricflow_semantics/naming/linkable_spec_name.py` | "double-underscore" qualified name like `listing__country__day`. |
| `structured_linkable_spec_name` | `StructuredLinkableSpecName` | data class | (same) | The parsed form: element name + entity links + time granularity. |
| `naming_scheme` | `NamingScheme` | sealed interface | `metricflow_semantics/naming/naming_scheme.py` | Pluggable name strategies. |
| `dunder_scheme` | `DunderScheme` | data object/class | `metricflow_semantics/naming/dunder_scheme.py` | The default. |
| `metric_scheme` | `MetricScheme` | data class | `metricflow_semantics/naming/metric_scheme.py` | |
| `object_builder_scheme` | `ObjectBuilderScheme` | data class | `metricflow_semantics/naming/object_builder_scheme.py` | |

### Lookup + semantic graph

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `semantic_manifest_lookup` | `SemanticManifestLookup` | class | `metricflow_semantics/model/semantic_manifest_lookup.py` | The compiled-and-indexed view of the manifest. Holds `metricLookup`, `semanticModelLookup`, etc. |
| `metric_lookup` | `MetricLookup` | class | `metricflow_semantics/model/semantics/metric_lookup.py` | |
| `semantic_model_lookup` | `SemanticModelLookup` | class | `metricflow_semantics/model/semantics/semantic_model_lookup.py` | |
| `dimension_lookup` | `DimensionLookup` | class | `metricflow_semantics/model/semantics/dimension_lookup.py` | |
| `linkable_element` | `LinkableElement` | sealed interface | `metricflow_semantics/model/semantics/linkable_element.py` | Closed family covering entity / dimension / metric / time-dimension. |
| `linkable_element_set` | `LinkableElementSet` | data class | `metricflow_semantics/model/semantics/linkable_element_set_base.py` | Container of linkable elements with metadata. |
| `linkable_element_property` | `LinkableElementProperty` | enum class | `metricflow_semantics/model/linkable_element_property.py` | Tag flags like `ENTITY`, `DERIVED_TIME_GRANULARITY`, `LOCAL_LINKED`, `METRIC`, `DATE_PART`. |
| `linkable_element_type` | `LinkableElementType` | enum class | (within linkable_element.py) | `ENTITY`, `DIMENSION`, `METRIC`, `TIME_DIMENSION`. |
| `annotated_spec` | `AnnotatedSpec` | data class | `model/semantics/linkable_element_set_base.py` | Spec + provenance + properties. |
| `semantic_graph` | `SemanticGraph` | class | `metricflow_semantics/semantic_graph/sg_interfaces.py` | The compiled graph of semantic models + joins. |
| `semantic_graph_node` | `SemanticGraphNode` | sealed interface | `metricflow_semantics/semantic_graph/nodes/*.py` | Nodes in the semantic graph. |
| `semantic_graph_edge` | `SemanticGraphEdge` | sealed interface | `metricflow_semantics/semantic_graph/edges/*.py` | Edges (join descriptions, derivations). |
| `attribute_recipe` | `AttributeRecipe` | data class | `metricflow_semantics/semantic_graph/attribute_resolution/attribute_recipe.py` | Sequence of steps to materialise a group-by item. |
| `dunder_name_trie` | `DunderNameTrie` | class | `semantic_graph/trie_resolver/dunder_name_trie.py` | Trie of qualified names for fast lookup. |

### Query parsing & resolution

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `metricflow_query_parser` | `MetricFlowQueryParser` | class | `metricflow_semantics/query/query_parser.py` | Top-level parser. |
| `query_resolver` | `QueryResolver` | class | `metricflow_semantics/query/query_resolver.py` | |
| `query_resolution_dag` | `QueryResolutionDag` | class | `query/group_by_item/resolution_dag/dag.py` | DAG of resolved query refs. |
| `query_resolution_node` | `QueryResolutionNode` | sealed interface | `query/group_by_item/resolution_dag/resolution_nodes/*.py` | |
| `group_by_item_resolver` | `GroupByItemResolver` | class | `query/group_by_item/group_by_item_resolver.py` | |
| `filter_spec_resolver` | `FilterSpecResolver` | class | `query/group_by_item/filter_spec_resolution/filter_spec_resolver.py` | |
| `metricflow_query_resolution_issue` | `MetricFlowQueryResolutionIssue` | sealed interface | `query/issues/issues_base.py` | Family of resolution problems. |
| `metric_query_parameter` | `MetricQueryParameter` | interface | `metricflow_semantics/protocols/query_parameter.py` | Public API surface. |
| `group_by_query_parameter` | `GroupByQueryParameter` | interface | (same) | |
| `time_dimension_query_parameter` | `TimeDimensionQueryParameter` | interface | (same) | |
| `order_by_query_parameter` | `OrderByQueryParameter` | interface | (same) | |
| `saved_query_parameter` | `SavedQueryParameter` | data class | `specs/query_param_implementations.py` | |

### Dataflow plan

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `dataflow_plan` | `DataflowPlan` | data class | `metricflow/dataflow/dataflow_plan.py` | The full IR; a DAG of nodes from sources to a sink Write node. |
| `dataflow_plan_node` | `DataflowPlanNode` | sealed interface | (same) | Base for all 22 dataflow node variants. |
| `dataflow_plan_visitor` | `DataflowPlanVisitor` | interface | `dataflow/dataflow_plan_visitor.py` | Generic visitor; one method per node type. |
| `dataflow_plan_builder` | `DataflowPlanBuilder` | class | `dataflow/builder/dataflow_plan_builder.py` | The thing that compiles a `MetricFlowQuerySpec` into a `DataflowPlan`. |
| `dataflow_plan_optimizer` | `DataflowPlanOptimizer` | sealed interface + impls | `dataflow/optimizer/dataflow_plan_optimizer.py` | |
| `read_sql_source_node` | `ReadSqlSourceNode` | data class | `dataflow/nodes/read_sql_source.py` | Leaf reading from a `SemanticModelDataSet`. |
| `compute_metrics_node` | `ComputeMetricsNode` | data class | `dataflow/nodes/compute_metrics.py` | |
| `aggregate_simple_metric_inputs_node` | `AggregateSimpleMetricInputsNode` | data class | `dataflow/nodes/aggregate_simple_metric_inputs.py` | |
| `join_on_entities_node` | `JoinOnEntitiesNode` | data class | `dataflow/nodes/join_to_base.py` | |
| `join_to_time_spine_node` | `JoinToTimeSpineNode` | data class | `dataflow/nodes/join_to_time_spine.py` | |
| `join_over_time_range_node` | `JoinOverTimeRangeNode` | data class | `dataflow/nodes/join_over_time.py` | Used for cumulative metrics. |
| `where_filter_node` | `WhereFilterNode` | data class | `dataflow/nodes/where_filter.py` | |
| `metric_time_dimension_transform_node` | `MetricTimeDimensionTransformNode` | data class | `dataflow/nodes/metric_time_transform.py` | The node that materialises `metric_time` from a measure's `agg_time_dimension`. |
| `write_to_result_data_table_node` | `WriteToResultDataTableNode` | data class | `dataflow/nodes/write_to_data_table.py` | Sink — produces a `MetricFlowDataTable`. |
| `write_to_result_table_node` | `WriteToResultTableNode` | data class | `dataflow/nodes/write_to_table.py` | Sink — writes to a named table. |

(Sixteen more node variants; same pattern.)

### Plan conversion + SQL plan

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `dataflow_to_sql_plan_converter` | `DataflowToSqlPlanConverter` | class | `metricflow/plan_conversion/to_sql_plan/dataflow_to_sql.py` | The visitor that walks dataflow nodes and emits a `SqlPlan`. |
| `sql_plan` | `SqlPlan` | data class | `metricflow/sql/sql_plan.py` | Tree of SQL plan nodes. |
| `sql_plan_node` | `SqlPlanNode` | sealed interface | `metricflow/sql/sql_*.py` | Base for SQL plan nodes. |
| `sql_select_statement_node` | `SqlSelectStatementNode` | data class | `metricflow/sql/sql_select_node.py` | Most common variant. |
| `sql_cte_node` | `SqlCteNode` | data class | `metricflow/sql/sql_cte_node.py` | |
| `sql_table_node` | `SqlTableNode` | data class | `metricflow/sql/sql_table_node.py` | |
| `sql_expression_node` | `SqlExpression` | sealed interface | `metricflow_semantics/sql/sql_exprs.py` | ~20 variants for column/binary/case-when/etc. |
| `sql_optimizer` | `SqlOptimizer` | sealed interface + impls | `metricflow/sql/optimizer/sql_query_plan_optimizer.py` | |
| `sql_optimization_level` | `SqlOptimizationLevel` | enum class | `metricflow/sql/optimizer/optimization_levels.py` | |
| `sql_bind_parameter_set` | `SqlBindParameterSet` | data class | `metricflow_semantics/sql/sql_bind_parameters.py` | |
| `sql_table` | `SqlTable` | data class | `metricflow_semantics/sql/sql_table.py` | Database-qualified table name. |

### SQL render

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `sql_plan_renderer` | `SqlPlanRenderer` | interface | `metricflow/sql/render/sql_plan_renderer.py` | The dialect-shaped contract. |
| `sql_plan_render_result` | `SqlPlanRenderResult` | data class | (same) | Holds rendered SQL string + bind parameters. |
| `sql_expression_renderer` | `SqlExpressionRenderer` | interface | `metricflow/sql/render/expr_renderer.py` | Sub-renderer used by `SqlPlanRenderer`. |
| `default_sql_plan_renderer` | `DefaultSqlPlanRenderer` | class | `sql/render/sql_plan_renderer.py` (same file as the interface) | The "ANSI SQL"-ish baseline. |
| `trino_sql_plan_renderer` | `TrinoSqlPlanRenderer` | class | `metricflow/sql/render/trino.py` | |
| `bigquery_sql_plan_renderer` | `BigQuerySqlPlanRenderer` | class | `metricflow/sql/render/big_query.py` | |
| `snowflake_sql_plan_renderer` | `SnowflakeSqlPlanRenderer` | class | `metricflow/sql/render/snowflake.py` | |
| `databricks_sql_plan_renderer` | `DatabricksSqlPlanRenderer` | class | `metricflow/sql/render/databricks.py` | |
| `redshift_sql_plan_renderer` | `RedshiftSqlPlanRenderer` | class | `metricflow/sql/render/redshift.py` | |
| `duckdb_sql_plan_renderer` | `DuckDbSqlPlanRenderer` | class | `metricflow/sql/render/duckdb_renderer.py` | Note: Python file is `duckdb_renderer.py` (renamed); Kotlin class stays `DuckDbSqlPlanRenderer`. |
| `postgres_sql_plan_renderer` | `PostgresSqlPlanRenderer` | class | `metricflow/sql/render/postgres.py` | Python class is `PostgresSQLSqlPlanRenderer` (note doubled SQL); Kotlin uses `PostgresSqlPlanRenderer` for consistency. |
| `sql_engine` | `SqlEngine` | enum class | `metricflow/protocols/sql_client.py` | `TRINO`, `BIGQUERY`, `SNOWFLAKE`, `DATABRICKS`, `REDSHIFT`, `DUCKDB`, `POSTGRES`. |

### Engine facade + outputs

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `metricflow_engine` | `MetricFlowEngine` | class | `metricflow/engine/metricflow_engine.py` | The 7 SQL-generation methods + validate manifest. |
| `metricflow_query_request` | `MetricFlowQueryRequest` | data class | (same) | Caller's request DTO. |
| `metricflow_explain_result` | `MetricFlowExplainResult` | data class | (same) | What `explain()` returns. |
| `metricflow_query_result` | (not ported — execution-only) | — | (same) | The `query()` return type. Excluded. |
| `metricflow_request_id` | `MetricFlowRequestId` | value class | (same) | |
| `metricflow_query_type` | `MetricFlowQueryType` | enum class | (same) | `STANDARD` vs `DIMENSION_VALUES`. |
| `dimension` (engine output) | `Dimension` | data class | `metricflow/engine/models.py` | **Different from manifest `Dimension`.** This one carries `dunderName`, `entityLinks`, `semanticModelReference`, etc. — the *runtime* shape. Lives in `application.engine`, not `domain.manifest.model`. |
| `entity` (engine output) | `Entity` | data class | (same) | |
| `metric` (engine output) | `Metric` | data class | (same) | |
| `saved_query` (engine output) | `SavedQuery` | data class | (same) | |
| `searchable_element` | `SearchableElement` | sealed interface | (same) | Closed over the four engine-output element types. |
| `sql_client` | `SqlClient` | interface | `metricflow/protocols/sql_client.py` | We keep `sqlEngineType`, `sqlPlanRenderer`, `renderBindParameterKey` — drop `query`, `execute`, `dryRun`, `close`. |

### Validation

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `semantic_manifest_validator` | `SemanticManifestValidator` | class | `metricflow_semantic_interfaces/validations/semantic_manifest_validator.py` | |
| `semantic_manifest_validation_rule` | `SemanticManifestValidationRule` | interface | `validations/validator_helpers.py` | |
| `validation_issue` | `ValidationIssue` | sealed interface | (same) | Three variants: `ValidationError`, `ValidationWarning`, `ValidationFutureError`. |
| `validation_issue_level` | (dropped — encoded in the type) | — | (same) | The `WARNING / FUTURE_ERROR / ERROR` enum is redundant with the sealed interface. |
| `validation_issue_context` | `ValidationIssueContext` | sealed interface | (same) | Where the issue happened (file / model / element). |

### Time

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `expanded_time_granularity` | `ExpandedTimeGranularity` | data class | `metricflow_semantics/time/granularity.py` | Standard `TimeGranularity` plus user-defined custom granularities. |
| `time_range_constraint` | `TimeRangeConstraint` | data class | `metricflow_semantics/filters/time_constraint.py` | |
| `time_period` | `TimePeriod` | data class | `metricflow_semantics/time/time_period.py` | |
| `time_source` | `TimeSource` | interface | `metricflow_semantics/time/time_source.py` | |
| `time_spine_source` | `TimeSpineSource` | class | `metricflow_semantics/time/time_spine_source.py` | |

### DAG + utility

| metricflow term | Kotlin name | Kind | File | Notes |
|---|---|---|---|---|
| `metricflow_dag` | `MetricFlowDag` | class | `metricflow_semantics/dag/mf_dag.py` | Generic DAG used by `DataflowPlan`, `SqlPlan`, `ExecutionPlan`. |
| `dag_node` | `DagNode` | abstract class | (same) | |
| `dag_id` | `DagId` | value class | (same) | |
| `node_id` | `NodeId` | value class | (same) | |
| `id_prefix` | `IdPrefix` | enum class | `metricflow_semantics/dag/id_prefix.py` | Prefix tags for sequential IDs (one per node type). |
| `sequential_id_generator` | `SequentialIdGenerator` | class | `metricflow_semantics/dag/sequential_id.py` | Thread-local counter. |
| `lazy_format` | `LazyFormat` | class / `Logger.lazy { }` extension | `metricflow_semantics/toolkit/mf_logging/lazy_formattable.py` | Lazy log message builder. |

## When you encounter a term not in this list

1. Check the Python source for the same idea — search by snake_case form.
2. PascalCase it directly. Don't paraphrase.
3. Add it to this glossary in a follow-up commit.

If the Python term is genuinely framework-specific (`HashableBaseModel`, `ProtocolHint`, `SerializableDataclass`), it disappears in Kotlin — the data class itself does the same job.
