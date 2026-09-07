-- Constrain Output with WHERE
-- Select: ['__txn_count']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  SUM(txn_count) AS txn_count
FROM (
  -- Join Standard Outputs
  -- Select: ['__txn_count', 'account_id__customer_id__customer_name']
  SELECT
    subq_11.customer_id__customer_name AS account_id__customer_id__customer_name
    , account_month_txns_src_10000.txn_count AS txn_count
  FROM mf_corpus_2026_05_11_static.account_month_txns account_month_txns_src_10000
  LEFT OUTER JOIN (
    -- Join Standard Outputs
    -- Select: ['customer_id__customer_name', 'ds_partitioned__day', 'account_id']
    SELECT
      DATE_TRUNC('day', bridge_table_src_10000.ds_partitioned) AS ds_partitioned__day
      , bridge_table_src_10000.account_id AS account_id
      , customer_table_src_10000.customer_name AS customer_id__customer_name
    FROM mf_corpus_2026_05_11_static.bridge_table bridge_table_src_10000
    LEFT OUTER JOIN
      mf_corpus_2026_05_11_static.customer_table customer_table_src_10000
    ON
      (
        bridge_table_src_10000.customer_id = customer_table_src_10000.customer_id
      ) AND (
        DATE_TRUNC('day', bridge_table_src_10000.ds_partitioned) = DATE_TRUNC('day', customer_table_src_10000.ds_partitioned)
      )
  ) subq_11
  ON
    (
      account_month_txns_src_10000.account_id = subq_11.account_id
    ) AND (
      DATE_TRUNC('day', account_month_txns_src_10000.ds_partitioned) = subq_11.ds_partitioned__day
    )
) subq_13
WHERE account_id__customer_id__customer_name IS NOT NULL
