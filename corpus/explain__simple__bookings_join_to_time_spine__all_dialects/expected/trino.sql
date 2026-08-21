-- Join to Time Spine Dataset
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  time_spine_src_10006.ds AS metric_time__day
  , subq_4.__bookings_join_to_time_spine AS bookings_join_to_time_spine
FROM mf_corpus_2026_05_11_static.mf_time_spine time_spine_src_10006
LEFT OUTER JOIN (
  -- Aggregate Inputs for Simple Metrics
  SELECT
    metric_time__day
    , SUM(__bookings_join_to_time_spine) AS __bookings_join_to_time_spine
  FROM (
    -- Read Elements From Semantic Model 'bookings_source'
    -- Metric Time Dimension 'ds'
    -- Select: ['__bookings_join_to_time_spine', 'metric_time__day']
    -- Select: ['__bookings_join_to_time_spine', 'metric_time__day']
    SELECT
      DATE_TRUNC('day', ds) AS metric_time__day
      , 1 AS __bookings_join_to_time_spine
    FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  ) subq_3
  GROUP BY
    metric_time__day
) subq_4
ON
  time_spine_src_10006.ds = subq_4.metric_time__day
