-- Join Self Over Time Range
-- Select: ['__revenue', 'metric_time__day']
-- Select: ['__revenue', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_3.ds AS metric_time__day
  , SUM(revenue_src_10000.revenue) AS trailing_2_months_revenue
FROM mf_corpus_2026_05_11_static.mf_time_spine subq_3
INNER JOIN
  mf_corpus_2026_05_11_static.fct_revenue revenue_src_10000
ON
  (
    DATETIME_TRUNC(revenue_src_10000.created_at, day) <= subq_3.ds
  ) AND (
    DATETIME_TRUNC(revenue_src_10000.created_at, day) > DATE_SUB(CAST(subq_3.ds AS DATETIME), INTERVAL 2 month)
  )
GROUP BY
  metric_time__day
