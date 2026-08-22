-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__month
  , SUM(__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Constrain Time Range to [2000-01-01T00:00:00, 2020-02-29T00:00:00]
  -- Select: ['__bookings', 'metric_time__month']
  -- Select: ['__bookings', 'metric_time__month']
  SELECT
    DATE_TRUNC('month', ds) AS metric_time__month
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  WHERE DATE_TRUNC('day', ds) BETWEEN timestamp '2000-01-01' AND timestamp '2020-02-29'
) subq_5
GROUP BY
  metric_time__month
