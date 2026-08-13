-- Read Elements From Semantic Model 'bookings_source'
-- Metric Time Dimension 'ds'
-- Select: ['__average_booking_value', 'metric_time__day']
-- Select: ['__average_booking_value', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  DATE_TRUNC('day', ds) AS metric_time__day
  , AVG(booking_value) AS average_booking_value
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
GROUP BY
  DATE_TRUNC('day', ds)
