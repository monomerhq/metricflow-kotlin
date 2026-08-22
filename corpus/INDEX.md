# Corpus index

Total cases: 136
Generated from disk by scanning `corpus/<case>/meta.json`.

| case_id | subcommand | manifest | dialects | source |
|---|---|---|---|---|
| `entities_for_metrics__simple__bookings` | `entities_for_metrics` | `simple_manifest` |  | (derived) |
| `entities_for_metrics__simple__bookings_views` | `entities_for_metrics` | `simple_manifest` |  | (derived) |
| `entities_for_metrics__simple_multi_hop_join__txn_count` | `entities_for_metrics` | `multi_hop_join_manifest` |  | (derived) |
| `explain__minimal_fixture__bookings_by_metric_time` | `explain` | `minimal_valid_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | python_oracle/tests/fixtures/minimal_valid_manifest.json |
| `explain__simple__average_booking_value` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookers_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__booking_fees_derived` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__booking_fees_per_booker_derived` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_all_time__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py |
| `explain__simple__bookings_all_time_with_time_constraint__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_no_window_with_time_constraint |
| `explain__simple__bookings_by_booking_is_instant` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_ds_month` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_ds_quarter` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_ds_week` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_ds_year` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_listing` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | derived (entity group_by) |
| `explain__simple__bookings_by_listing_country` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_query_rendering.py::test_local_dimension_using_local_entity (variant) |
| `explain__simple__bookings_by_metric_time__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/integration/test_rendered_query.py::test_render_query |
| `explain__simple__bookings_by_metric_time_with_limit` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_query_rendering.py::test_limit_rows |
| `explain__simple__bookings_by_metric_time_with_order` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_by_month_with_end_only__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | metricflow_semantics/query/query_parser.py::_adjust_time_constraint |
| `explain__simple__bookings_by_month_with_start_only__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | metricflow_semantics/query/query_parser.py::_adjust_time_constraint |
| `explain__simple__bookings_custom_alien_day__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_custom_granularity.py::test_simple_metric_with_custom_granularity |
| `explain__simple__bookings_growth_2_weeks__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_derived_metric_with_offset_window |
| `explain__simple__bookings_growth_2_weeks_with_time_constraint__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_time_offset_metric_with_time_constraint |
| `explain__simple__bookings_growth_since_start_of_month__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_derived_metric_with_offset_to_grain |
| `explain__simple__bookings_join_to_time_spine__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_time_spine_join_rendering.py |
| `explain__simple__bookings_listings_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/integration/test_rendered_query.py::test_id_enumeration |
| `explain__simple__bookings_listings_views_multi` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_no_groupby` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | derived (no group_by) |
| `explain__simple__bookings_offset_one_alien_day_by_alien_day__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_custom_granularity.py::test_custom_offset_window_with_only_window_grain |
| `explain__simple__bookings_offset_one_alien_day_by_day__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_custom_granularity.py::test_custom_offset_window |
| `explain__simple__bookings_order_output_columns_by_input_order` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_per_dollar_ratio` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_per_listing_ratio` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_with_time_constraint` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__bookings_with_where` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__identity_verifications_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__instant_bookings_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__max_min_booking_value` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__revenue_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__revenue_mtd_by_month__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_grain_to_date |
| `explain__simple__subdaily_cumulative_grain_to_date_hour__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_cumulative_grain_to_date_metric |
| `explain__simple__subdaily_cumulative_multiple_time_spines__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_custom_granularity.py::test_multiple_time_spines_in_query_for_cumulative_metric |
| `explain__simple__subdaily_join_multiple_time_spines__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_custom_granularity.py::test_multiple_time_spines_in_query_for_join_to_time_spine_metric |
| `explain__simple__subdaily_offset_to_grain_hour__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_offset_to_grain_metric |
| `explain__simple__subdaily_offset_window_hour__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_offset_window_metric |
| `explain__simple__trailing_2_months_revenue__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric |
| `explain__simple__trailing_2_months_revenue_by_month__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py |
| `explain__simple__trailing_2_months_revenue_with_time_constraint__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_with_time_constraint |
| `explain__simple__views_by_listing` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__views_by_metric_time` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `explain__simple__visit_buy_conversion_rate_7days__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_conversion_metric_rendering.py::test_conversion_metric_with_window |
| `explain__simple__visit_buy_conversion_rate_7days_with_filter_only__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_conversion_metric_rendering.py |
| `explain__simple__visit_buy_conversion_rate_7days_with_time_constraint__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/query_rendering/test_conversion_metric_rendering.py::test_conversion_metric_with_window_and_time_constraint |
| `explain__simple__visit_buy_conversion_rate_by_session__all_dialects` | `explain` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | tests_metricflow/plan_conversion/dataflow_to_sql/test_conversion_metrics_to_sql.py::test_conversion_rate_with_constant_properties |
| `explain_get_dimension_values__simple__listing_country_for_bookings` | `explain_get_dimension_values` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | MetricFlowEngine.explain_get_dimension_values |
| `explain_get_dimension_values__simple__metric_time_for_bookings` | `explain_get_dimension_values` | `simple_manifest` | Trino,BigQuery,Snowflake,Databricks,Redshift,DuckDB,Postgres | (derived) |
| `list_dimensions__ambiguous_resolution_manifest__all` | `list_dimensions` | `ambiguous_resolution_manifest` |  | (derived) |
| `list_dimensions__cyclic_join_manifest__all` | `list_dimensions` | `cyclic_join_manifest` |  | (derived) |
| `list_dimensions__data_warehouse_validation_manifest__all` | `list_dimensions` | `data_warehouse_validation_manifest` |  | (derived) |
| `list_dimensions__derived_metrics_manifest__all` | `list_dimensions` | `derived_metrics_manifest` |  | (derived) |
| `list_dimensions__extended_date_manifest__all` | `list_dimensions` | `extended_date_manifest` |  | (derived) |
| `list_dimensions__join_types_manifest__all` | `list_dimensions` | `join_types_manifest` |  | (derived) |
| `list_dimensions__multi_hop_join_manifest__all` | `list_dimensions` | `multi_hop_join_manifest` |  | (derived) |
| `list_dimensions__name_edge_case_manifest__all` | `list_dimensions` | `name_edge_case_manifest` |  | (derived) |
| `list_dimensions__non_sm_manifest__all` | `list_dimensions` | `non_sm_manifest` |  | (derived) |
| `list_dimensions__partitioned_multi_hop_join_manifest__all` | `list_dimensions` | `partitioned_multi_hop_join_manifest` |  | (derived) |
| `list_dimensions__scd_manifest__all` | `list_dimensions` | `scd_manifest` |  | (derived) |
| `list_dimensions__sg_00_minimal_manifest__all` | `list_dimensions` | `sg_00_minimal_manifest` |  | (derived) |
| `list_dimensions__sg_02_single_join__all` | `list_dimensions` | `sg_02_single_join` |  | (derived) |
| `list_dimensions__sg_05_derived_metric__all` | `list_dimensions` | `sg_05_derived_metric` |  | (derived) |
| `list_dimensions__simple__all` | `list_dimensions` | `simple_manifest` |  | (derived) |
| `list_dimensions__simple__for_bookings` | `list_dimensions` | `simple_manifest` |  | (derived) |
| `list_dimensions__simple__for_views` | `list_dimensions` | `simple_manifest` |  | (derived) |
| `list_dimensions__simple_manifest__all` | `list_dimensions` | `simple_manifest` |  | (derived) |
| `list_dimensions__simple_multi_hop_join_manifest__all` | `list_dimensions` | `simple_multi_hop_join_manifest` |  | (derived) |
| `list_group_bys__simple__bookings` | `list_group_bys` | `simple_manifest` |  | (derived) |
| `list_group_bys__simple__bookings_dunder_order` | `list_group_bys` | `simple_manifest` |  | (derived) |
| `list_group_bys__simple__bookings_semantic_model_order` | `list_group_bys` | `simple_manifest` |  | (derived) |
| `list_group_bys__simple__views` | `list_group_bys` | `simple_manifest` |  | (derived) |
| `list_group_bys__simple_multi_hop_join__txn_count` | `list_group_bys` | `multi_hop_join_manifest` |  | (derived) |
| `list_metrics__ambiguous_resolution_manifest__with_dimensions` | `list_metrics` | `ambiguous_resolution_manifest` |  | (derived) |
| `list_metrics__cyclic_join_manifest__with_dimensions` | `list_metrics` | `cyclic_join_manifest` |  | (derived) |
| `list_metrics__data_warehouse_validation_manifest__with_dimensions` | `list_metrics` | `data_warehouse_validation_manifest` |  | (derived) |
| `list_metrics__derived_metrics_manifest__with_dimensions` | `list_metrics` | `derived_metrics_manifest` |  | (derived) |
| `list_metrics__extended_date_manifest__with_dimensions` | `list_metrics` | `extended_date_manifest` |  | (derived) |
| `list_metrics__join_types_manifest__with_dimensions` | `list_metrics` | `join_types_manifest` |  | (derived) |
| `list_metrics__multi_hop_join_manifest__with_dimensions` | `list_metrics` | `multi_hop_join_manifest` |  | (derived) |
| `list_metrics__name_edge_case_manifest__with_dimensions` | `list_metrics` | `name_edge_case_manifest` |  | (derived) |
| `list_metrics__non_sm_manifest__with_dimensions` | `list_metrics` | `non_sm_manifest` |  | (derived) |
| `list_metrics__partitioned_multi_hop_join_manifest__with_dimensions` | `list_metrics` | `partitioned_multi_hop_join_manifest` |  | (derived) |
| `list_metrics__scd_manifest__with_dimensions` | `list_metrics` | `scd_manifest` |  | (derived) |
| `list_metrics__sg_00_minimal_manifest__with_dimensions` | `list_metrics` | `sg_00_minimal_manifest` |  | (derived) |
| `list_metrics__sg_02_single_join__with_dimensions` | `list_metrics` | `sg_02_single_join` |  | (derived) |
| `list_metrics__sg_05_derived_metric__with_dimensions` | `list_metrics` | `sg_05_derived_metric` |  | (derived) |
| `list_metrics__simple__with_dimensions` | `list_metrics` | `simple_manifest` |  | MetricFlowEngine.list_metrics(include_dimensions=True) |
| `list_metrics__simple__without_dimensions` | `list_metrics` | `simple_manifest` |  | (derived) |
| `list_metrics__simple_manifest__with_dimensions` | `list_metrics` | `simple_manifest` |  | (derived) |
| `list_metrics__simple_multi_hop_join_manifest__with_dimensions` | `list_metrics` | `simple_multi_hop_join_manifest` |  | (derived) |
| `list_saved_queries__ambiguous_resolution_manifest` | `list_saved_queries` | `ambiguous_resolution_manifest` |  | (derived) |
| `list_saved_queries__cyclic_join_manifest` | `list_saved_queries` | `cyclic_join_manifest` |  | (derived) |
| `list_saved_queries__data_warehouse_validation_manifest` | `list_saved_queries` | `data_warehouse_validation_manifest` |  | (derived) |
| `list_saved_queries__derived_metrics_manifest` | `list_saved_queries` | `derived_metrics_manifest` |  | (derived) |
| `list_saved_queries__extended_date_manifest` | `list_saved_queries` | `extended_date_manifest` |  | (derived) |
| `list_saved_queries__join_types_manifest` | `list_saved_queries` | `join_types_manifest` |  | (derived) |
| `list_saved_queries__multi_hop_join_manifest` | `list_saved_queries` | `multi_hop_join_manifest` |  | (derived) |
| `list_saved_queries__name_edge_case_manifest` | `list_saved_queries` | `name_edge_case_manifest` |  | (derived) |
| `list_saved_queries__non_sm_manifest` | `list_saved_queries` | `non_sm_manifest` |  | (derived) |
| `list_saved_queries__partitioned_multi_hop_join_manifest` | `list_saved_queries` | `partitioned_multi_hop_join_manifest` |  | (derived) |
| `list_saved_queries__scd_manifest` | `list_saved_queries` | `scd_manifest` |  | (derived) |
| `list_saved_queries__sg_00_minimal_manifest` | `list_saved_queries` | `sg_00_minimal_manifest` |  | (derived) |
| `list_saved_queries__sg_02_single_join` | `list_saved_queries` | `sg_02_single_join` |  | (derived) |
| `list_saved_queries__sg_05_derived_metric` | `list_saved_queries` | `sg_05_derived_metric` |  | (derived) |
| `list_saved_queries__simple` | `list_saved_queries` | `simple_manifest` |  | (derived) |
| `list_saved_queries__simple_manifest` | `list_saved_queries` | `simple_manifest` |  | (derived) |
| `list_saved_queries__simple_multi_hop_join_manifest` | `list_saved_queries` | `simple_multi_hop_join_manifest` |  | (derived) |
| `validate_manifest__ambiguous_resolution_manifest` | `validate_manifest` | `ambiguous_resolution_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__config_linter_manifest_invalid` | `validate_manifest` | `config_linter_manifest` |  | metricflow_semantics/test_helpers/semantic_manifest_yamls/config_linter_manifest/* (intentional errors) |
| `validate_manifest__cyclic_join_manifest` | `validate_manifest` | `cyclic_join_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__data_warehouse_validation_manifest` | `validate_manifest` | `data_warehouse_validation_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__derived_metrics_manifest` | `validate_manifest` | `derived_metrics_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__extended_date_manifest` | `validate_manifest` | `extended_date_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__join_types_manifest` | `validate_manifest` | `join_types_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__minimal_fixture` | `validate_manifest` | `minimal_valid_manifest` |  | (derived) |
| `validate_manifest__minimal_invalid_fixture` | `validate_manifest` | `minimal_invalid_manifest` |  | (derived) |
| `validate_manifest__multi_hop_join_manifest` | `validate_manifest` | `multi_hop_join_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__name_edge_case_manifest` | `validate_manifest` | `name_edge_case_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__non_sm_manifest` | `validate_manifest` | `non_sm_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__partitioned_multi_hop_join_manifest` | `validate_manifest` | `partitioned_multi_hop_join_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__scd_manifest` | `validate_manifest` | `scd_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__sg_00_minimal_manifest` | `validate_manifest` | `sg_00_minimal_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__sg_02_single_join` | `validate_manifest` | `sg_02_single_join` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__sg_05_derived_metric` | `validate_manifest` | `sg_05_derived_metric` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__simple_manifest` | `validate_manifest` | `simple_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
| `validate_manifest__simple_multi_hop_join_manifest` | `validate_manifest` | `simple_multi_hop_join_manifest` |  | SemanticManifestValidator on canonical YAML manifest |
