-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__hour
  , archived_users AS subdaily_offset_window_metric
FROM (
  -- Join to Time Spine Dataset
  -- Compute Metrics via Expressions
  SELECT
    time_spine_src_10005.ts AS metric_time__hour
    , subq_4.__archived_users AS archived_users
  FROM mf_corpus_2026_05_11_static.mf_time_spine_hour time_spine_src_10005
  INNER JOIN (
    -- Aggregate Inputs for Simple Metrics
    SELECT
      metric_time__hour
      , SUM(__archived_users) AS __archived_users
    FROM (
      -- Read Elements From Semantic Model 'users_ds_source'
      -- Metric Time Dimension 'archived_at'
      -- Select: ['__archived_users', 'metric_time__hour']
      -- Select: ['__archived_users', 'metric_time__hour']
      SELECT
        DATETIME_TRUNC(archived_at, hour) AS metric_time__hour
        , 1 AS __archived_users
      FROM mf_corpus_2026_05_11_static.dim_users users_ds_source_src_10000
    ) subq_3
    GROUP BY
      metric_time__hour
  ) subq_4
  ON
    DATE_SUB(CAST(time_spine_src_10005.ts AS DATETIME), INTERVAL 1 hour) = subq_4.metric_time__hour
) subq_10
