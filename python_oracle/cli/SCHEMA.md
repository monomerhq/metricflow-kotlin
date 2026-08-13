# Oracle CLI — Input / Output JSON Schema

Each subcommand reads one JSON document from stdin and writes one JSON document
to stdout. Errors are reported on stderr with a non-zero exit code.

## Shared input envelope

For every subcommand the input is an object that carries the semantic manifest
in the same shape that ``PydanticSemanticManifest.parse_obj`` accepts (i.e. the
dict that metricflow's YAML loader produces). The manifest pieces are:

| Key | Type | Notes |
|---|---|---|
| `semantic_models` | array of objects | One ``PydanticSemanticModel`` dict per semantic model. |
| `metrics` | array of objects | One ``PydanticMetric`` dict per metric. |
| `project_configuration` | object | ``PydanticProjectConfiguration`` dict. **Must** include either ``time_spines`` or ``time_spine_table_configurations`` for any non-trivial query. |
| `saved_queries` | array of objects | Optional. Defaults to ``[]``. |
| `sql_engine` | string | Optional. One of ``TRINO`` (default), ``BIGQUERY``, ``SNOWFLAKE``, ``DATABRICKS``, ``REDSHIFT``, ``DUCKDB``, ``POSTGRES``. Case-insensitive. |
| `args` | object | Subcommand-specific argument bundle. See per-command tables below. ``validate_manifest`` ignores ``args``. |

A canonical minimal valid input fixture lives at
``python_oracle/tests/fixtures/minimal_valid_manifest.json``.

## Shared output rules

- Output is always a single JSON object on stdout.
- Field order is not significant.
- Datetimes are ISO-8601 strings.
- Enums are rendered with their ``.value`` (e.g. ``"simple"``, ``"day"``,
  ``"ERROR"``).
- The metricflow domain vocabulary is preserved on the wire
  (``dunder_name``, ``semantic_model_reference``, ``entity_links``, ...).

---

## `explain`

Renders the SQL for a metric query via ``MetricFlowEngine.explain``.

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `metric_names` | array of string | one of metric / saved_query must be set | |
| `group_by_names` | array of string | no | E.g. ``["metric_time__day"]``. |
| `where_constraints` | array of string | no | SQL where clauses. |
| `order_by_names` | array of string | no | Prefix with ``"-"`` for descending. |
| `limit` | integer | no | |
| `time_constraint_start` | string (ISO 8601) | no | |
| `time_constraint_end` | string (ISO 8601) | no | |
| `saved_query_name` | string | no | Mutually exclusive with ``metric_names`` / ``group_by_names``. |
| `min_max_only` | bool | no | Default ``false``. |
| `apply_group_by` | bool | no | Default ``true``. |
| `order_output_columns_by_input_order` | bool | no | Default ``false``. |

### Output

| Key | Type | Notes |
|---|---|---|
| `sql` | string | Rendered SQL statement. |
| `bind_parameters` | object | `{"param_items": [{"key": str, "value": {"value": Any, "python_type": str}}, ...]}`. |
| `metadata.sql_engine` | string | The dialect enum value (e.g. ``"Trino"``). |
| `metadata.dataflow_plan_id` | string | The dataflow plan's DAG id. |
| `metadata.query_spec_summary` | object | ``metric_specs`` / ``dimension_specs`` / ``time_dimension_specs`` / ``entity_specs`` / ``min_max_only``. |

---

## `list_metrics`

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `include_dimensions` | bool | no | Default ``true``. |

### Output

| Key | Type | Notes |
|---|---|---|
| `metrics` | array of metric objects | See below. |

Each metric object:

| Key | Type |
|---|---|
| `name` | string |
| `type` | string (``"simple"``, ``"derived"``, ``"ratio"``, ``"cumulative"``, ``"conversion"``) |
| `description` | string / null |
| `label` | string / null |
| `type_params` | object (Pydantic dump) |
| `filter` | object / null |
| `metadata` | object / null |
| `config` | object / null |
| `dimensions` | array of dimension objects (see ``list_dimensions``) |
| `semantic_models` | array of string |

---

## `list_dimensions`

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `metric_names` | array of string | no | ``null`` / absent => all dimensions in the manifest. |

### Output

| Key | Type | Notes |
|---|---|---|
| `dimensions` | array of dimension objects | |

Dimension object:

| Key | Type |
|---|---|
| `name` | string |
| `dunder_name` | string |
| `qualified_name` | string (same as ``dunder_name``; kept for caller convenience) |
| `type` | string (``"categorical"`` / ``"time"``) |
| `granularity` | string / null |
| `entity_links` | array of string |
| `semantic_model_name` | string / null (null for ``metric_time``) |
| `is_partition` | bool |
| `is_metric_time` | bool |
| `description` | string / null |
| `label` | string / null |
| `expr` | string / null |
| `metadata` | object / null |

---

## `entities_for_metrics`

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `metric_names` | array of string | yes | |

### Output

| Key | Type | Notes |
|---|---|---|
| `entities` | array of entity objects | |

Entity object:

| Key | Type |
|---|---|
| `name` | string |
| `type` | string (``"primary"`` / ``"foreign"`` / ``"unique"`` / ``"natural"``) |
| `role` | string / null |
| `semantic_model_name` | string |
| `description` | string / null |
| `expr` | string / null |

---

## `list_group_bys`

Combined view: dimensions and entities for a metric set (or all dimensions when
``metric_names`` is absent).

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `metric_names` | array of string | no | |
| `include_derived_time_granularities` | bool | no | Default ``false``. |
| `order_by` | string | no | ``"DUNDER_NAME"`` (default) or ``"SEMANTIC_MODEL_NAME"``. |

### Output

| Key | Type |
|---|---|
| `dimensions` | array of dimension objects (same shape as ``list_dimensions``) |
| `entities` | array of entity objects (same shape as ``entities_for_metrics``) |

---

## `list_saved_queries`

No ``args``.

### Output

| Key | Type |
|---|---|
| `saved_queries` | array of saved-query objects |

Saved-query object:

| Key | Type |
|---|---|
| `name` | string |
| `description` | string / null |
| `label` | string / null |
| `query_params` | object (Pydantic dump of ``SavedQueryQueryParams``) |
| `metadata` | object / null |
| `exports` | array of export objects |
| `tags` | array of string |

---

## `explain_get_dimension_values`

Render the SQL that would fetch distinct values for a dimension.

### Input — `args`

| Key | Type | Required | Notes |
|---|---|---|---|
| `metric_names` | array of string | yes | |
| `get_group_by_values` | string | yes | Dimension name (e.g. ``"metric_time__day"``). |
| `time_constraint_start` | string (ISO 8601) | no | |
| `time_constraint_end` | string (ISO 8601) | no | |
| `min_max_only` | bool | no | Default ``false``. |

### Output

| Key | Type |
|---|---|
| `sql` | string |
| `bind_parameters` | object (same shape as ``explain``) |
| `metadata.sql_engine` | string |

---

## `validate_manifest`

Run ``SemanticManifestValidator.validate_semantic_manifest`` on the manifest
(after applying ``PydanticSemanticManifestTransformer`` so the validator sees
what a query call would see). Does **not** raise on blocking issues — the
issue list is always returned.

### Input

Just the manifest fields (no ``args``).

### Output

| Key | Type | Notes |
|---|---|---|
| `issues` | array of issue objects | Concatenated ``errors`` ++ ``future_errors`` ++ ``warnings`` (matches ``all_issues``). |
| `error_count` | integer | Count of blocking errors. |
| `future_error_count` | integer | |
| `warning_count` | integer | |
| `has_blocking_issues` | bool | True iff ``error_count > 0``. |

Issue object:

| Key | Type | Notes |
|---|---|---|
| `level` | string | ``"ERROR"`` / ``"FUTURE_ERROR"`` / ``"WARNING"``. |
| `message` | string | |
| `context` | object / null | Pydantic dump of the rule's ``ValidationContext`` (file/object/element). |
| `context_str` | string | Human-readable rendering of the context. |
| `extra_detail` | string / null | |
| `readable` | string | ``issue.as_readable_str(verbose=False)``. |
| `error_date` | string (ISO 8601) | Only present for ``FUTURE_ERROR`` issues. |
