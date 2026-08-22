-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__month
  , SUM(__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Constrain Time Range to [2020-01-01T00:00:00, 2040-12-31T00:00:00]
  -- Select: ['__bookings', 'metric_time__month']
  -- Select: ['__bookings', 'metric_time__month']
  SELECT
    DATETIME_TRUNC(ds, month) AS metric_time__month
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  WHERE DATETIME_TRUNC(ds, day) BETWEEN '2020-01-01' AND '2040-12-31'
) subq_5
GROUP BY
  metric_time__month
