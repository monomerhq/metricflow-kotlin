-- Join on MAX(snapshot_date) and [] grouping by None
-- Select: ['__mrr']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  SUM(subq_4.__mrr) AS mrr
FROM (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__mrr', 'snapshot_date__day']
  SELECT
    DATE_TRUNC('day', month) AS snapshot_date__day
    , mrr_usd AS __mrr
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
) subq_4
INNER JOIN (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__mrr', 'snapshot_date__day']
  -- Filter row on MAX(snapshot_date__day)
  SELECT
    MAX(DATE_TRUNC('day', month)) AS snapshot_date__day__complete
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
) subq_6
ON
  subq_4.snapshot_date__day = subq_6.snapshot_date__day__complete
