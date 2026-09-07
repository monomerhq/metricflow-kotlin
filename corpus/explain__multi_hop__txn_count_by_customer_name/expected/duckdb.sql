-- Join Standard Outputs
-- Select: ['__txn_count', 'account_id__customer_id__customer_name']
-- Select: ['__txn_count', 'account_id__customer_id__customer_name']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  subq_9.customer_id__customer_name AS account_id__customer_id__customer_name
  , SUM(account_month_txns_src_10000.txn_count) AS txn_count
FROM mf_corpus_2026_05_11_static.account_month_txns account_month_txns_src_10000
LEFT OUTER JOIN (
  -- Join Standard Outputs
  -- Select: ['customer_id__customer_name', 'account_id']
  SELECT
    bridge_table_src_10000.account_id AS account_id
    , customer_table_src_10000.customer_name AS customer_id__customer_name
  FROM mf_corpus_2026_05_11_static.bridge_table bridge_table_src_10000
  LEFT OUTER JOIN
    mf_corpus_2026_05_11_static.customer_table customer_table_src_10000
  ON
    bridge_table_src_10000.customer_id = customer_table_src_10000.customer_id
) subq_9
ON
  account_month_txns_src_10000.account_id = subq_9.account_id
GROUP BY
  subq_9.customer_id__customer_name
