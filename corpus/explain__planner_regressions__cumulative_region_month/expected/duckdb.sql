-- Re-aggregate Metric via Group By
-- Write to DataTable
SELECT
  metric_time__month
  , active_accounts
FROM (
  -- Window Function for Metric Re-aggregation
  SELECT
    metric_time__month
    , LAST_VALUE(active_accounts) OVER (
      PARTITION BY metric_time__month
      ORDER BY metric_time__day
      ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
    ) AS active_accounts
  FROM (
    -- Constrain Output with WHERE
    -- Constrain Time Range to [2025-10-01T00:00:00, 2026-10-31T00:00:00]
    -- Select: ['__daily_active_accounts', 'metric_time__month', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    -- Compute Metrics via Expressions
    -- Compute Metrics via Expressions
    SELECT
      metric_time__day
      , metric_time__month
      , COUNT(DISTINCT daily_active_accounts) AS active_accounts
    FROM (
      -- Join Standard Outputs
      -- Select: ['__daily_active_accounts', 'account_day__active_users', 'account__region', 'metric_time__month', 'metric_time__day']
      SELECT
        subq_12.metric_time__day AS metric_time__day
        , subq_12.metric_time__month AS metric_time__month
        , subq_12.account_day__active_users AS account_day__active_users
        , accounts_src_10000.region AS account__region
        , subq_12.__daily_active_accounts AS daily_active_accounts
      FROM (
        -- Join Self Over Time Range
        SELECT
          subq_10.metric_time__day AS metric_time__day
          , subq_10.metric_time__month AS metric_time__month
          , subq_9.account AS account
          , subq_9.account_day__active_users AS account_day__active_users
          , subq_9.__daily_active_accounts AS __daily_active_accounts
        FROM (
          -- Read From Time Spine 'time_spine_day'
          SELECT
            date_day AS metric_time__day
            , DATE_TRUNC('month', date_day) AS metric_time__month
          FROM acme.time_spine_day subq_11
          WHERE date_day BETWEEN '2025-10-01' AND '2026-10-31'
        ) subq_10
        INNER JOIN (
          -- Read Elements From Semantic Model 'daily_account_activity'
          -- Metric Time Dimension 'activity_date'
          -- Constrain Time Range to [2025-09-24T00:00:00, 2026-10-31T00:00:00]
          SELECT
            DATE_TRUNC('day', activity_date) AS metric_time__day
            , account_id AS account
            , active_users AS account_day__active_users
            , account_id AS __daily_active_accounts
          FROM acme.daily_account_activity daily_account_activity_src_10000
          WHERE DATE_TRUNC('day', activity_date) BETWEEN '2025-09-24' AND '2026-10-31'
        ) subq_9
        ON
          (
            subq_9.metric_time__day <= subq_10.metric_time__day
          ) AND (
            subq_9.metric_time__day > subq_10.metric_time__day - INTERVAL 7 day
          )
      ) subq_12
      LEFT OUTER JOIN
        acme.accounts accounts_src_10000
      ON
        subq_12.account = accounts_src_10000.account_id
    ) subq_16
    WHERE (
      metric_time__day BETWEEN '2025-10-01' AND '2026-10-31'
    ) AND (
      (
        account_day__active_users >= 1
      ) AND (
        account__region = 'North America'
      )
    )
    GROUP BY
      metric_time__day
      , metric_time__month
  ) subq_22
) subq_23
GROUP BY
  metric_time__month
  , active_accounts
