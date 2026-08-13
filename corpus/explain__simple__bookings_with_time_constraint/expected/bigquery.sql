-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , SUM(__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Constrain Time Range to [2020-01-01T00:00:00, 2020-01-31T00:00:00]
  -- Select: ['__bookings', 'metric_time__day']
  -- Select: ['__bookings', 'metric_time__day']
  SELECT
    DATETIME_TRUNC(ds, day) AS metric_time__day
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  WHERE DATETIME_TRUNC(ds, day) BETWEEN '2020-01-01' AND '2020-01-31'
) subq_5
GROUP BY
  metric_time__day
