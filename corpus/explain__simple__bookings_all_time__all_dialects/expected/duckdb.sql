-- Join Self Over Time Range
-- Select: ['__bookings', 'metric_time__day']
-- Select: ['__bookings', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_3.ds AS metric_time__day
  , SUM(subq_1.__bookings) AS bookings_all_time
FROM mf_corpus_2026_05_11_static.mf_time_spine subq_3
INNER JOIN (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_1
ON
  (subq_1.metric_time__day <= subq_3.ds)
GROUP BY
  subq_3.ds
