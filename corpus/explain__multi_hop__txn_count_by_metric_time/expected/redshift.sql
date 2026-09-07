-- Read Elements From Semantic Model 'account_month_txns'
-- Metric Time Dimension 'ds'
-- Select: ['__txn_count', 'metric_time__day']
-- Select: ['__txn_count', 'metric_time__day']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  DATE_TRUNC('day', ds) AS metric_time__day
  , SUM(txn_count) AS txn_count
FROM mf_corpus_2026_05_11_static.account_month_txns account_month_txns_src_10000
GROUP BY
  DATE_TRUNC('day', ds)
