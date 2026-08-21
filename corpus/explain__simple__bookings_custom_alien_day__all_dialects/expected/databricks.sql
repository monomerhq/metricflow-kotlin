-- Metric Time Dimension 'ds'
-- Join to Custom Granularity Dataset
-- Select: ['__bookings', 'metric_time__alien_day']
-- Select: ['__bookings', 'metric_time__alien_day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_1.alien_day AS metric_time__alien_day
  , SUM(subq_0.__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  SELECT
    1 AS __bookings
    , DATE_TRUNC('day', ds) AS ds__day
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_0
LEFT OUTER JOIN
  mf_corpus_2026_05_11_static.mf_time_spine subq_1
ON
  subq_0.ds__day = subq_1.ds
GROUP BY
  subq_1.alien_day
