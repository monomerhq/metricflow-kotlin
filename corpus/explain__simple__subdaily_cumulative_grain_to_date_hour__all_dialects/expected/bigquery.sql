-- Join Self Over Time Range
-- Select: ['__simple_subdaily_metric_default_day', 'metric_time__hour']
-- Select: ['__simple_subdaily_metric_default_day', 'metric_time__hour']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_3.ts AS metric_time__hour
  , SUM(subq_1.__simple_subdaily_metric_default_day) AS subdaily_cumulative_grain_to_date_metric
FROM mf_corpus_2026_05_11_static.mf_time_spine_hour subq_3
INNER JOIN (
  -- Read Elements From Semantic Model 'users_ds_source'
  -- Metric Time Dimension 'archived_at'
  SELECT
    DATETIME_TRUNC(archived_at, hour) AS metric_time__hour
    , 1 AS __simple_subdaily_metric_default_day
  FROM mf_corpus_2026_05_11_static.dim_users users_ds_source_src_10000
) subq_1
ON
  (
    subq_1.metric_time__hour <= subq_3.ts
  ) AND (
    subq_1.metric_time__hour >= DATETIME_TRUNC(subq_3.ts, hour)
  )
GROUP BY
  metric_time__hour
