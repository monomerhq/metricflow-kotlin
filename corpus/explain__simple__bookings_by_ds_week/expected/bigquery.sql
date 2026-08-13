-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__week
  , SUM(__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__bookings', 'metric_time__week']
  -- Select: ['__bookings', 'metric_time__week']
  SELECT
    DATETIME_TRUNC(ds, isoweek) AS metric_time__week
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_3
GROUP BY
  metric_time__week
