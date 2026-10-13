-- Join on MAX(snapshot_date) and ['account'] grouping by None
-- Select: ['__per_account_last']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  SUM(subq_4.__per_account_last) AS per_account_last
FROM (
  -- Read Elements From Semantic Model 'irregular_snapshots'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__per_account_last', 'snapshot_date__day', 'account']
  SELECT
    DATE_TRUNC('day', snapshot_date) AS snapshot_date__day
    , account_id AS account
    , value AS __per_account_last
  FROM acme.irregular_snapshots irregular_snapshots_src_10000
  WHERE DATE_TRUNC('day', snapshot_date) BETWEEN '2025-10-13' AND '2026-10-12'
) subq_4
INNER JOIN (
  -- Read Elements From Semantic Model 'irregular_snapshots'
  -- Metric Time Dimension 'snapshot_date'
  -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
  -- Select: ['__per_account_last', 'snapshot_date__day', 'account']
  -- Filter row on MAX(snapshot_date__day)
  SELECT
    account_id AS account
    , MAX(DATE_TRUNC('day', snapshot_date)) AS snapshot_date__day__complete
  FROM acme.irregular_snapshots irregular_snapshots_src_10000
  WHERE DATE_TRUNC('day', snapshot_date) BETWEEN '2025-10-13' AND '2026-10-12'
  GROUP BY
    account_id
) subq_6
ON
  (
    subq_4.snapshot_date__day = subq_6.snapshot_date__day__complete
  ) AND (
    subq_4.account = subq_6.account
  )
