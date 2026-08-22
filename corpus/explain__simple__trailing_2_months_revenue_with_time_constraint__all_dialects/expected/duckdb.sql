-- Join Self Over Time Range
-- Select: ['__revenue', 'metric_time__day']
-- Constrain Time Range to [2020-01-01T00:00:00, 2020-01-01T00:00:00]
-- Select: ['__revenue', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_7.metric_time__day AS metric_time__day
  , SUM(subq_6.__revenue) AS trailing_2_months_revenue
FROM (
  -- Read From Time Spine 'mf_time_spine'
  SELECT
    ds AS metric_time__day
  FROM mf_corpus_2026_05_11_static.mf_time_spine subq_8
  WHERE ds BETWEEN '2020-01-01' AND '2020-01-01'
) subq_7
INNER JOIN (
  -- Read Elements From Semantic Model 'revenue'
  -- Metric Time Dimension 'ds'
  -- Constrain Time Range to [2019-11-01T00:00:00, 2020-01-01T00:00:00]
  SELECT
    DATE_TRUNC('day', created_at) AS metric_time__day
    , revenue AS __revenue
  FROM mf_corpus_2026_05_11_static.fct_revenue revenue_src_10000
  WHERE DATE_TRUNC('day', created_at) BETWEEN '2019-11-01' AND '2020-01-01'
) subq_6
ON
  (
    subq_6.metric_time__day <= subq_7.metric_time__day
  ) AND (
    subq_6.metric_time__day > subq_7.metric_time__day - INTERVAL 2 month
  )
WHERE subq_7.metric_time__day BETWEEN '2020-01-01' AND '2020-01-01'
GROUP BY
  subq_7.metric_time__day
