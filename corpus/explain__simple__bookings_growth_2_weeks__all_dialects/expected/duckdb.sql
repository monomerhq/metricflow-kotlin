-- Compute Metrics via Expressions
-- Write to DataTable
WITH sma_10009_cte AS (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
)

SELECT
  metric_time__day AS metric_time__day
  , bookings - bookings_2_weeks_ago AS bookings_growth_2_weeks
FROM (
  -- Combine Aggregated Outputs
  SELECT
    COALESCE(subq_5.metric_time__day, subq_15.metric_time__day) AS metric_time__day
    , MAX(subq_5.bookings) AS bookings
    , MAX(subq_15.bookings_2_weeks_ago) AS bookings_2_weeks_ago
  FROM (
    -- Read From CTE For node_id=sma_10009
    -- Select: ['__bookings', 'metric_time__day']
    -- Select: ['__bookings', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    -- Compute Metrics via Expressions
    SELECT
      metric_time__day
      , SUM(__bookings) AS bookings
    FROM sma_10009_cte
    GROUP BY
      metric_time__day
  ) subq_5
  FULL OUTER JOIN (
    -- Join to Time Spine Dataset
    -- Compute Metrics via Expressions
    SELECT
      time_spine_src_10006.ds AS metric_time__day
      , subq_9.__bookings AS bookings_2_weeks_ago
    FROM mf_corpus_2026_05_11_static.mf_time_spine time_spine_src_10006
    INNER JOIN (
      -- Read From CTE For node_id=sma_10009
      -- Select: ['__bookings', 'metric_time__day']
      -- Select: ['__bookings', 'metric_time__day']
      -- Aggregate Inputs for Simple Metrics
      SELECT
        metric_time__day
        , SUM(__bookings) AS __bookings
      FROM sma_10009_cte
      GROUP BY
        metric_time__day
    ) subq_9
    ON
      time_spine_src_10006.ds - INTERVAL 14 day = subq_9.metric_time__day
  ) subq_15
  ON
    subq_5.metric_time__day = subq_15.metric_time__day
  GROUP BY
    COALESCE(subq_5.metric_time__day, subq_15.metric_time__day)
) subq_16
