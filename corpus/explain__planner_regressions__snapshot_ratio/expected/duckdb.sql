-- Join on MAX(snapshot_date) and [] grouping by None
-- Select: ['__mrr', '__paid_accounts']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  CAST(SUM(subq_5.__mrr) AS DOUBLE) / CAST(NULLIF(SUM(subq_5.__paid_accounts), 0) AS DOUBLE) AS arpa
FROM (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__mrr', '__paid_accounts', 'snapshot_date__day']
  SELECT
    DATE_TRUNC('day', month) AS snapshot_date__day
    , mrr_usd AS __mrr
    , CASE WHEN mrr_usd > 0 THEN 1 ELSE 0 END AS __paid_accounts
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
) subq_5
INNER JOIN (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__mrr', '__paid_accounts', 'snapshot_date__day']
  -- Filter row on MAX(snapshot_date__day)
  SELECT
    MAX(DATE_TRUNC('day', month)) AS snapshot_date__day__complete
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
  WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
) subq_7
ON
  subq_5.snapshot_date__day = subq_7.snapshot_date__day__complete
