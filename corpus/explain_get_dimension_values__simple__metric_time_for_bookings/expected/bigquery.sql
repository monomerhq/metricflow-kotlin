-- Read Elements From Semantic Model 'bookings_source'
-- Metric Time Dimension 'ds'
-- Select: ['__bookings', 'metric_time__day']
-- Select: ['__bookings', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Select: ['metric_time__day']
-- Write to DataTable
SELECT
  DATETIME_TRUNC(ds, day) AS metric_time__day
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
GROUP BY
  metric_time__day
