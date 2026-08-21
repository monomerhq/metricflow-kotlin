-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__alien_day
  , booking__ds__alien_day
  , bookings AS bookings_offset_one_alien_day
FROM (
  -- Join to Time Spine Dataset
  -- Select: ['__bookings', 'metric_time__alien_day', 'booking__ds__alien_day']
  -- Select: ['__bookings', 'metric_time__alien_day', 'booking__ds__alien_day']
  -- Aggregate Inputs for Simple Metrics
  -- Compute Metrics via Expressions
  SELECT
    subq_4.metric_time__alien_day AS metric_time__alien_day
    , subq_4.booking__ds__alien_day AS booking__ds__alien_day
    , SUM(subq_2.__bookings) AS bookings
  FROM (
    -- Join Offset Custom Granularity to Base Granularity
    WITH cte_2 AS (
      -- Read From Time Spine 'mf_time_spine'
      SELECT
        ds AS ds__day
        , alien_day AS ds__alien_day
      FROM mf_corpus_2026_05_11_static.mf_time_spine time_spine_src_10006
    )

    SELECT
      cte_2.ds__day AS ds__day
      , subq_3.ds__alien_day__lead AS metric_time__alien_day
      , subq_3.ds__alien_day__lead AS booking__ds__alien_day
    FROM cte_2
    INNER JOIN (
      -- Offset Custom Granularity
      SELECT
        ds__alien_day
        , LEAD(ds__alien_day, 1) OVER (ORDER BY ds__alien_day) AS ds__alien_day__lead
      FROM cte_2
      GROUP BY
        ds__alien_day
    ) subq_3
    ON
      cte_2.ds__alien_day = subq_3.ds__alien_day
  ) subq_4
  INNER JOIN (
    -- Read Elements From Semantic Model 'bookings_source'
    -- Metric Time Dimension 'ds'
    SELECT
      DATETIME_TRUNC(ds, day) AS metric_time__day
      , 1 AS __bookings
    FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  ) subq_2
  ON
    subq_4.ds__day = subq_2.metric_time__day
  GROUP BY
    metric_time__alien_day
    , booking__ds__alien_day
) subq_11
