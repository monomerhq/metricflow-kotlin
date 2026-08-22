-- Join Self Over Time Range
-- Select: ['__bookings', 'metric_time__day']
-- Constrain Time Range to [2020-01-01T00:00:00, 2020-01-01T00:00:00]
-- Select: ['__bookings', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_7.metric_time__day AS metric_time__day
  , SUM(subq_6.__bookings) AS bookings_all_time
FROM (
  -- Read From Time Spine 'mf_time_spine'
  SELECT
    ds AS metric_time__day
  FROM mf_corpus_2026_05_11_static.mf_time_spine subq_8
  WHERE ds BETWEEN timestamp '2020-01-01' AND timestamp '2020-01-01'
) subq_7
INNER JOIN (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  -- Constrain Time Range to [2000-01-01T00:00:00, 2020-01-01T00:00:00]
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  WHERE DATE_TRUNC('day', ds) BETWEEN timestamp '2000-01-01' AND timestamp '2020-01-01'
) subq_6
ON
  (subq_6.metric_time__day <= subq_7.metric_time__day)
WHERE subq_7.metric_time__day BETWEEN timestamp '2020-01-01' AND timestamp '2020-01-01'
GROUP BY
  subq_7.metric_time__day
