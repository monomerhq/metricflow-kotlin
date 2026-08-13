-- Constrain Output with WHERE
-- Select: ['__bookings', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , SUM(bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__bookings', 'booking__is_instant', 'metric_time__day']
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , is_instant AS booking__is_instant
    , 1 AS bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_2
WHERE booking__is_instant = true
GROUP BY
  metric_time__day
