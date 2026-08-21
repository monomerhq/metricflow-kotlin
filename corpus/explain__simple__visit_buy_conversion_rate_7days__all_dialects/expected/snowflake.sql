-- Compute Metrics via Expressions
-- Write to DataTable
WITH sma_10019_cte AS (
  -- Read Elements From Semantic Model 'visits_source'
  -- Metric Time Dimension 'ds'
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , user_id AS user
    , 1 AS __visits
  FROM mf_corpus_2026_05_11_static.fct_visits visits_source_src_10000
)

SELECT
  metric_time__day AS metric_time__day
  , CAST(__buys AS DOUBLE) / CAST(NULLIF(__visits, 0) AS DOUBLE) AS visit_buy_conversion_rate_7days
FROM (
  -- Combine Aggregated Outputs
  SELECT
    COALESCE(subq_5.metric_time__day, subq_17.metric_time__day) AS metric_time__day
    , MAX(subq_5.__visits) AS __visits
    , MAX(subq_17.__buys) AS __buys
  FROM (
    -- Constrain Output with WHERE
    -- Select: ['__visits', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    SELECT
      metric_time__day
      , SUM(visits) AS __visits
    FROM (
      -- Read From CTE For node_id=sma_10019
      -- Select: ['__visits', 'metric_time__day']
      SELECT
        metric_time__day
        , __visits AS visits
      FROM sma_10019_cte
    ) subq_2
    WHERE metric_time__day = '2020-01-01'
    GROUP BY
      metric_time__day
  ) subq_5
  FULL OUTER JOIN (
    -- Find conversions for user within the range of 7 day
    -- Select: ['__buys', 'metric_time__day']
    -- Select: ['__buys', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    SELECT
      metric_time__day
      , SUM(__buys) AS __buys
    FROM (
      -- Dedupe the fanout with mf_internal_uuid in the conversion data set
      SELECT DISTINCT
        FIRST_VALUE(subq_9.__visits) OVER (
          PARTITION BY
            subq_12.user
            , subq_12.metric_time__day
            , subq_12.mf_internal_uuid
          ORDER BY subq_9.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS __visits
        , FIRST_VALUE(subq_9.metric_time__day) OVER (
          PARTITION BY
            subq_12.user
            , subq_12.metric_time__day
            , subq_12.mf_internal_uuid
          ORDER BY subq_9.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS metric_time__day
        , FIRST_VALUE(subq_9.user) OVER (
          PARTITION BY
            subq_12.user
            , subq_12.metric_time__day
            , subq_12.mf_internal_uuid
          ORDER BY subq_9.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS user
        , subq_12.mf_internal_uuid AS mf_internal_uuid
        , subq_12.__buys AS __buys
      FROM (
        -- Constrain Output with WHERE
        -- Select: ['__visits', 'metric_time__day', 'user']
        SELECT
          metric_time__day
          , subq_7.user
          , visits AS __visits
        FROM (
          -- Read From CTE For node_id=sma_10019
          -- Select: ['__visits', 'metric_time__day', 'user']
          SELECT
            metric_time__day
            , sma_10019_cte.user
            , __visits AS visits
          FROM sma_10019_cte
        ) subq_7
        WHERE metric_time__day = '2020-01-01'
      ) subq_9
      INNER JOIN (
        -- Read Elements From Semantic Model 'buys_source'
        -- Metric Time Dimension 'ds'
        -- Add column with generated UUID
        SELECT
          DATE_TRUNC('day', ds) AS metric_time__day
          , user_id AS user
          , 1 AS __buys
          , UUID_STRING() AS mf_internal_uuid
        FROM mf_corpus_2026_05_11_static.fct_buys buys_source_src_10000
      ) subq_12
      ON
        (
          subq_9.user = subq_12.user
        ) AND (
          (
            subq_9.metric_time__day <= subq_12.metric_time__day
          ) AND (
            subq_9.metric_time__day > DATEADD(day, -7, subq_12.metric_time__day)
          )
        )
    ) subq_13
    GROUP BY
      metric_time__day
  ) subq_17
  ON
    subq_5.metric_time__day = subq_17.metric_time__day
  GROUP BY
    COALESCE(subq_5.metric_time__day, subq_17.metric_time__day)
) subq_18
