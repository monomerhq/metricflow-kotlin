-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , booking_value * 0.05 AS booking_fees
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__booking_value', 'metric_time__day']
  -- Select: ['__booking_value', 'metric_time__day']
  -- Aggregate Inputs for Simple Metrics
  -- Compute Metrics via Expressions
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , SUM(booking_value) AS booking_value
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  GROUP BY
    DATE_TRUNC('day', ds)
) subq_5
