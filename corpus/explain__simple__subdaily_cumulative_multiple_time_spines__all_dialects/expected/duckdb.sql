-- Join Self Over Time Range
-- Join to Custom Granularity Dataset
-- Select: ['__simple_subdaily_metric_default_day', 'metric_time__alien_day', 'metric_time__hour']
-- Select: ['__simple_subdaily_metric_default_day', 'metric_time__alien_day', 'metric_time__hour']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_4.alien_day AS metric_time__alien_day
  , subq_3.ts AS metric_time__hour
  , SUM(subq_1.__simple_subdaily_metric_default_day) AS subdaily_cumulative_window_metric
FROM mf_corpus_2026_05_11_static.mf_time_spine_hour subq_3
INNER JOIN (
  -- Read Elements From Semantic Model 'users_ds_source'
  -- Metric Time Dimension 'archived_at'
  SELECT
    DATE_TRUNC('hour', archived_at) AS metric_time__hour
    , 1 AS __simple_subdaily_metric_default_day
  FROM mf_corpus_2026_05_11_static.dim_users users_ds_source_src_10000
) subq_1
ON
  (
    subq_1.metric_time__hour <= subq_3.ts
  ) AND (
    subq_1.metric_time__hour > subq_3.ts - INTERVAL 3 hour
  )
LEFT OUTER JOIN
  mf_corpus_2026_05_11_static.mf_time_spine subq_4
ON
  DATE_TRUNC('day', subq_3.ts) = subq_4.ds
GROUP BY
  subq_4.alien_day
  , subq_3.ts
