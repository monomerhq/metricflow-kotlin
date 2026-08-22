-- Compute Metrics via Expressions
-- Write to DataTable
WITH sma_10019_cte AS (
  -- Read Elements From Semantic Model 'visits_source'
  -- Metric Time Dimension 'ds'
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , user_id AS user
    , session_id AS session
    , referrer_id AS visit__referrer_id
    , 1 AS __visits
  FROM mf_corpus_2026_05_11_static.fct_visits visits_source_src_10000
)

SELECT
  metric_time__day AS metric_time__day
  , visit__referrer_id AS visit__referrer_id
  , CAST(__buys AS DOUBLE) / CAST(NULLIF(__visits, 0) AS DOUBLE) AS visit_buy_conversion_rate_by_session
FROM (
  -- Combine Aggregated Outputs
  SELECT
    COALESCE(subq_4.metric_time__day, subq_15.metric_time__day) AS metric_time__day
    , COALESCE(subq_4.visit__referrer_id, subq_15.visit__referrer_id) AS visit__referrer_id
    , MAX(subq_4.__visits) AS __visits
    , MAX(subq_15.__buys) AS __buys
  FROM (
    -- Read From CTE For node_id=sma_10019
    -- Select: ['__visits', 'visit__referrer_id', 'metric_time__day']
    -- Select: ['__visits', 'visit__referrer_id', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    SELECT
      metric_time__day
      , visit__referrer_id
      , SUM(__visits) AS __visits
    FROM sma_10019_cte
    GROUP BY
      metric_time__day
      , visit__referrer_id
  ) subq_4
  FULL OUTER JOIN (
    -- Find conversions for user within the range of 7 day
    -- Select: ['__buys', 'visit__referrer_id', 'metric_time__day']
    -- Select: ['__buys', 'visit__referrer_id', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    SELECT
      metric_time__day
      , visit__referrer_id
      , SUM(__buys) AS __buys
    FROM (
      -- Dedupe the fanout with mf_internal_uuid in the conversion data set
      SELECT DISTINCT
        FIRST_VALUE(sma_10019_cte.__visits) OVER (
          PARTITION BY
            subq_10.user
            , subq_10.metric_time__day
            , subq_10.mf_internal_uuid
            , subq_10.session_id
          ORDER BY sma_10019_cte.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS __visits
        , FIRST_VALUE(sma_10019_cte.visit__referrer_id) OVER (
          PARTITION BY
            subq_10.user
            , subq_10.metric_time__day
            , subq_10.mf_internal_uuid
            , subq_10.session_id
          ORDER BY sma_10019_cte.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS visit__referrer_id
        , FIRST_VALUE(sma_10019_cte.metric_time__day) OVER (
          PARTITION BY
            subq_10.user
            , subq_10.metric_time__day
            , subq_10.mf_internal_uuid
            , subq_10.session_id
          ORDER BY sma_10019_cte.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS metric_time__day
        , FIRST_VALUE(sma_10019_cte.user) OVER (
          PARTITION BY
            subq_10.user
            , subq_10.metric_time__day
            , subq_10.mf_internal_uuid
            , subq_10.session_id
          ORDER BY sma_10019_cte.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS user
        , FIRST_VALUE(sma_10019_cte.session) OVER (
          PARTITION BY
            subq_10.user
            , subq_10.metric_time__day
            , subq_10.mf_internal_uuid
            , subq_10.session_id
          ORDER BY sma_10019_cte.metric_time__day DESC
          ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING
        ) AS session
        , subq_10.mf_internal_uuid AS mf_internal_uuid
        , subq_10.__buys AS __buys
      FROM sma_10019_cte
      INNER JOIN (
        -- Read Elements From Semantic Model 'buys_source'
        -- Metric Time Dimension 'ds'
        -- Add column with generated UUID
        SELECT
          DATE_TRUNC('day', ds) AS metric_time__day
          , user_id AS user
          , session_id
          , 1 AS __buys
          , GEN_RANDOM_UUID() AS mf_internal_uuid
        FROM mf_corpus_2026_05_11_static.fct_buys buys_source_src_10000
      ) subq_10
      ON
        (
          sma_10019_cte.user = subq_10.user
        ) AND (
          sma_10019_cte.session = subq_10.session_id
        ) AND (
          (
            sma_10019_cte.metric_time__day <= subq_10.metric_time__day
          ) AND (
            sma_10019_cte.metric_time__day > subq_10.metric_time__day - INTERVAL 7 day
          )
        )
    ) subq_11
    GROUP BY
      metric_time__day
      , visit__referrer_id
  ) subq_15
  ON
    (
      subq_4.visit__referrer_id = subq_15.visit__referrer_id
    ) AND (
      subq_4.metric_time__day = subq_15.metric_time__day
    )
  GROUP BY
    COALESCE(subq_4.metric_time__day, subq_15.metric_time__day)
    , COALESCE(subq_4.visit__referrer_id, subq_15.visit__referrer_id)
) subq_16
