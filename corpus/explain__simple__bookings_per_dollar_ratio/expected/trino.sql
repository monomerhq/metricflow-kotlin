-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , CAST(bookings AS DOUBLE) / CAST(NULLIF(booking_value, 0) AS DOUBLE) AS bookings_per_dollar
FROM (
  -- Aggregate Inputs for Simple Metrics
  -- Compute Metrics via Expressions
  SELECT
    metric_time__day
    , SUM(__bookings) AS bookings
    , SUM(__booking_value) AS booking_value
  FROM (
    -- Read Elements From Semantic Model 'bookings_source'
    -- Metric Time Dimension 'ds'
    -- Select: ['__bookings', '__booking_value', 'metric_time__day']
    -- Select: ['__bookings', '__booking_value', 'metric_time__day']
    SELECT
      DATE_TRUNC('day', ds) AS metric_time__day
      , 1 AS __bookings
      , booking_value AS __booking_value
    FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
  ) subq_3
  GROUP BY
    metric_time__day
) subq_5
