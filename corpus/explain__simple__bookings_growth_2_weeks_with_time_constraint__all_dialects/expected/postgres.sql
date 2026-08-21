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
    COALESCE(subq_8.metric_time__day, subq_20.metric_time__day) AS metric_time__day
    , MAX(subq_8.bookings) AS bookings
    , MAX(subq_20.bookings_2_weeks_ago) AS bookings_2_weeks_ago
  FROM (
    -- Read From CTE For node_id=sma_10009
    -- Constrain Time Range to [2019-12-19T00:00:00, 2020-01-02T00:00:00]
    -- Select: ['__bookings', 'metric_time__day']
    -- Select: ['__bookings', 'metric_time__day']
    -- Aggregate Inputs for Simple Metrics
    -- Compute Metrics via Expressions
    SELECT
      metric_time__day
      , SUM(__bookings) AS bookings
    FROM sma_10009_cte
    WHERE metric_time__day BETWEEN '2019-12-19' AND '2020-01-02'
    GROUP BY
      metric_time__day
  ) subq_8
  FULL OUTER JOIN (
    -- Join to Time Spine Dataset
    -- Constrain Time Range to [2019-12-19T00:00:00, 2020-01-02T00:00:00]
    -- Compute Metrics via Expressions
    SELECT
      subq_17.metric_time__day AS metric_time__day
      , subq_12.__bookings AS bookings_2_weeks_ago
    FROM (
      -- Read From Time Spine 'mf_time_spine'
      -- Change Column Aliases
      -- Select: ['metric_time__day']
      -- Constrain Time Range to [2019-12-19T00:00:00, 2020-01-02T00:00:00]
      -- Select: ['metric_time__day']
      SELECT
        ds AS metric_time__day
      FROM mf_corpus_2026_05_11_static.mf_time_spine time_spine_src_10006
      WHERE ds BETWEEN '2019-12-19' AND '2020-01-02'
    ) subq_17
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
    ) subq_12
    ON
      subq_17.metric_time__day - MAKE_INTERVAL(days => 14) = subq_12.metric_time__day
    WHERE subq_17.metric_time__day BETWEEN '2019-12-19' AND '2020-01-02'
  ) subq_20
  ON
    subq_8.metric_time__day = subq_20.metric_time__day
  GROUP BY
    COALESCE(subq_8.metric_time__day, subq_20.metric_time__day)
) subq_21
