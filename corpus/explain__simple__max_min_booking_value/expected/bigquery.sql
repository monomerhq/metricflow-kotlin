-- Read Elements From Semantic Model 'bookings_source'
-- Metric Time Dimension 'ds'
-- Select: ['__max_booking_value', '__min_booking_value', 'metric_time__day']
-- Select: ['__max_booking_value', '__min_booking_value', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  DATETIME_TRUNC(ds, day) AS metric_time__day
  , MAX(booking_value) AS max_booking_value
  , MIN(booking_value) AS min_booking_value
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
GROUP BY
  metric_time__day
