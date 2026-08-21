-- Join to Time Spine Dataset
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_10.metric_time__alien_day AS metric_time__alien_day
  , subq_10.metric_time__hour AS metric_time__hour
  , subq_5.__subdaily_join_to_time_spine_metric AS subdaily_join_to_time_spine_metric
FROM (
  -- Change Column Aliases
  -- Join to Custom Granularity Dataset
  -- Select: ['metric_time__alien_day', 'metric_time__hour']
  -- Select: ['metric_time__alien_day', 'metric_time__hour']
  SELECT
    subq_7.alien_day AS metric_time__alien_day
    , time_spine_src_10005.ts AS metric_time__hour
  FROM mf_corpus_2026_05_11_static.mf_time_spine_hour time_spine_src_10005
  LEFT OUTER JOIN
    mf_corpus_2026_05_11_static.mf_time_spine subq_7
  ON
    DATETIME_TRUNC(time_spine_src_10005.ts, day) = subq_7.ds
) subq_10
LEFT OUTER JOIN (
  -- Metric Time Dimension 'archived_at'
  -- Join to Custom Granularity Dataset
  -- Select: ['__subdaily_join_to_time_spine_metric', 'metric_time__alien_day', 'metric_time__hour']
  -- Select: ['__subdaily_join_to_time_spine_metric', 'metric_time__alien_day', 'metric_time__hour']
  -- Aggregate Inputs for Simple Metrics
  SELECT
    subq_1.alien_day AS metric_time__alien_day
    , subq_0.archived_at__hour AS metric_time__hour
    , SUM(subq_0.__subdaily_join_to_time_spine_metric) AS __subdaily_join_to_time_spine_metric
  FROM (
    -- Read Elements From Semantic Model 'users_ds_source'
    SELECT
      1 AS __subdaily_join_to_time_spine_metric
      , DATETIME_TRUNC(archived_at, hour) AS archived_at__hour
      , DATETIME_TRUNC(archived_at, day) AS archived_at__day
    FROM mf_corpus_2026_05_11_static.dim_users users_ds_source_src_10000
  ) subq_0
  LEFT OUTER JOIN
    mf_corpus_2026_05_11_static.mf_time_spine subq_1
  ON
    subq_0.archived_at__day = subq_1.ds
  GROUP BY
    metric_time__alien_day
    , metric_time__hour
) subq_5
ON
  subq_10.metric_time__hour = subq_5.metric_time__hour
