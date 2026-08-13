-- Read Elements From Semantic Model 'bookings_source'
-- Metric Time Dimension 'ds'
-- Select: ['__bookers', 'metric_time__day']
-- Select: ['__bookers', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  DATETIME_TRUNC(ds, day) AS metric_time__day
  , COUNT(DISTINCT guest_id) AS bookers
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
GROUP BY
  metric_time__day
