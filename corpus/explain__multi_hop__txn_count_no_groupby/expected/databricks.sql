-- Read Elements From Semantic Model 'account_month_txns'
-- Metric Time Dimension 'ds'
-- Select: ['__txn_count']
-- Select: ['__txn_count']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  SUM(txn_count) AS txn_count
FROM mf_corpus_2026_05_11_static.account_month_txns account_month_txns_src_10000
