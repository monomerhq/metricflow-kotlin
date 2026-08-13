-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , SUM(__instant_bookings) AS instant_bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__instant_bookings', 'metric_time__day']
  -- Select: ['__instant_bookings', 'metric_time__day']
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , CASE WHEN is_instant THEN 1 ELSE 0 END AS __instant_bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_3
GROUP BY
  metric_time__day
