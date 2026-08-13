-- Read Elements From Semantic Model 'revenue'
-- Metric Time Dimension 'ds'
-- Select: ['__revenue', 'metric_time__day']
-- Select: ['__revenue', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  DATE_TRUNC('day', created_at) AS metric_time__day
  , SUM(revenue) AS revenue
FROM mf_corpus_2026_05_11_static.fct_revenue revenue_src_10000
GROUP BY
  DATE_TRUNC('day', created_at)
