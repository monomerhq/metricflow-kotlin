-- Re-aggregate Metric via Group By
-- Write to DataTable
SELECT
  metric_time__month
  , revenue_mtd
FROM (
  -- Window Function for Metric Re-aggregation
  SELECT
    metric_time__month
    , FIRST_VALUE(revenue_mtd) OVER (
      PARTITION BY metric_time__month
      ORDER BY metric_time__day
      ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS revenue_mtd
  FROM (
    -- Join Self Over Time Range
    -- Select: ['__revenue', 'metric_time__month', 'metric_time__day']
    -- Select: ['__revenue', 'metric_time__month', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    -- Compute Metrics via Expressions
    -- Compute Metrics via Expressions
    SELECT
      subq_3.ds AS metric_time__day
      , DATE_TRUNC('month', subq_3.ds) AS metric_time__month
      , SUM(revenue_src_10000.revenue) AS revenue_mtd
    FROM mf_corpus_2026_05_11_static.mf_time_spine subq_3
    INNER JOIN
      mf_corpus_2026_05_11_static.fct_revenue revenue_src_10000
    ON
      (
        DATE_TRUNC('day', revenue_src_10000.created_at) <= subq_3.ds
      ) AND (
        DATE_TRUNC('day', revenue_src_10000.created_at) >= DATE_TRUNC('month', subq_3.ds)
      )
    GROUP BY
      subq_3.ds
      , DATE_TRUNC('month', subq_3.ds)
  ) subq_9
) subq_10
GROUP BY
  metric_time__month
  , revenue_mtd
