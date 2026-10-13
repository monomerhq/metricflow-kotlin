-- Combine Aggregated Outputs
-- Compute Metrics via Expressions
-- Write to DataTable
WITH sma_10003_cte AS (
  -- Read Elements From Semantic Model 'monthly_account_mrr'
  -- Metric Time Dimension 'snapshot_date'
  SELECT
    DATE_TRUNC('day', month) AS snapshot_date__day
    , DATE_TRUNC('day', month) AS metric_time__day
    , mrr_usd AS __mrr
    , mrr_usd AS __first
  FROM acme.monthly_account_mrr monthly_account_mrr_src_10000
)

SELECT
  CAST(MAX(subq_11.mrr) AS DOUBLE) / CAST(NULLIF(MAX(subq_20.first), 0) AS DOUBLE) AS last_to_first
FROM (
  -- Join on MAX(snapshot_date) and [] grouping by None
  -- Select: ['__mrr']
  -- Aggregate Inputs for Simple Metrics
  -- Compute Metrics via Expressions
  SELECT
    SUM(subq_5.__mrr) AS mrr
  FROM (
    -- Read From CTE For node_id=sma_10003
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    -- Select: ['__mrr', 'snapshot_date__day']
    SELECT
      snapshot_date__day
      , __mrr
    FROM sma_10003_cte
    WHERE metric_time__day BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_5
  INNER JOIN (
    -- Read From CTE For node_id=sma_10003
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    -- Select: ['__mrr', 'snapshot_date__day']
    -- Filter row on MAX(snapshot_date__day)
    SELECT
      MAX(snapshot_date__day) AS snapshot_date__day__complete
    FROM sma_10003_cte
    WHERE metric_time__day BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_7
  ON
    subq_5.snapshot_date__day = subq_7.snapshot_date__day__complete
) subq_11
CROSS JOIN (
  -- Join on MIN(snapshot_date) and [] grouping by None
  -- Select: ['__first']
  -- Aggregate Inputs for Simple Metrics
  -- Compute Metrics via Expressions
  SELECT
    SUM(subq_14.__first) AS first
  FROM (
    -- Read From CTE For node_id=sma_10003
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    -- Select: ['__first', 'snapshot_date__day']
    SELECT
      snapshot_date__day
      , __first
    FROM sma_10003_cte
    WHERE metric_time__day BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_14
  INNER JOIN (
    -- Read From CTE For node_id=sma_10003
    -- Constrain Time Range to [2025-10-13T00:00:00, 2026-10-12T00:00:00]
    -- Select: ['__first', 'snapshot_date__day']
    -- Filter row on MIN(snapshot_date__day)
    SELECT
      MIN(snapshot_date__day) AS snapshot_date__day__complete
    FROM sma_10003_cte
    WHERE metric_time__day BETWEEN '2025-10-13' AND '2026-10-12'
  ) subq_16
  ON
    subq_14.snapshot_date__day = subq_16.snapshot_date__day__complete
) subq_20
