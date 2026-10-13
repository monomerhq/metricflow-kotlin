-- Join on MAX(snapshot_date) and [] grouping by metric_time
-- Select: ['__mrr', 'metric_time__month']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_4.metric_time__month AS metric_time__month
  , SUM(subq_4.__mrr) AS mrr
FROM (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-01T00:00:00, 2026-10-31T00:00:00]
  -- Select: ['__mrr', 'metric_time__month', 'snapshot_date__day']
  SELECT
    DATE_TRUNC('day', month) AS snapshot_date__day
    , DATE_TRUNC('month', month) AS metric_time__month
    , mrr_usd AS __mrr
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-01' AND '2026-10-31'
) subq_4
INNER JOIN (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-01T00:00:00, 2026-10-31T00:00:00]
  -- Select: ['__mrr', 'metric_time__month', 'snapshot_date__day']
  -- Filter row on MAX(snapshot_date__day)
  SELECT
    DATE_TRUNC('month', month) AS metric_time__month
    , MAX(DATE_TRUNC('day', month)) AS snapshot_date__day__complete
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-01' AND '2026-10-31'
  GROUP BY
    DATE_TRUNC('month', month)
) subq_6
ON
  subq_4.snapshot_date__day = subq_6.snapshot_date__day__complete
GROUP BY
  subq_4.metric_time__month
