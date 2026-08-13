-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__year
  , SUM(__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__bookings', 'metric_time__year']
  -- Select: ['__bookings', 'metric_time__year']
  SELECT
    DATETIME_TRUNC(ds, year) AS metric_time__year
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_3
GROUP BY
  metric_time__year
