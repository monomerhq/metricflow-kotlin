-- Join on MAX(snapshot_date) and [] grouping by None
-- Select: ['__mrr', 'account__region']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_7.account__region AS account__region
  , SUM(subq_7.__mrr) AS mrr
FROM (
  -- Join Standard Outputs
  -- Select: ['__mrr', 'account__region', 'snapshot_date__day']
  SELECT
    subq_3.snapshot_date__day AS snapshot_date__day
    , accounts_src_10000.region AS account__region
    , subq_3.__mrr AS __mrr
  FROM (
    -- Read Elements From Semantic Model 'monthly_account_mrr'
    -- Metric Time Dimension 'snapshot_date'
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    SELECT
      DATE_TRUNC('day', month) AS snapshot_date__day
      , account_id AS account
      , mrr_usd AS __mrr
    FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
    WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_3
  LEFT OUTER JOIN
    acme.accounts accounts_src_10000
  ON
    subq_3.account = accounts_src_10000.account_id
) subq_7
INNER JOIN (
  -- Join Standard Outputs
  -- Select: ['__mrr', 'account__region', 'snapshot_date__day']
  -- Filter row on MAX(snapshot_date__day)
  SELECT
    MAX(subq_3.snapshot_date__day) AS snapshot_date__day__complete
  FROM (
    -- Read Elements From Semantic Model 'monthly_account_mrr'
    -- Metric Time Dimension 'snapshot_date'
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    SELECT
      DATE_TRUNC('day', month) AS snapshot_date__day
      , account_id AS account
    FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
    WHERE DATE_TRUNC('day', month) BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_3
  LEFT OUTER JOIN
    acme.accounts accounts_src_10000
  ON
    subq_3.account = accounts_src_10000.account_id
) subq_9
ON
  subq_7.snapshot_date__day = subq_9.snapshot_date__day__complete
GROUP BY
  subq_7.account__region
