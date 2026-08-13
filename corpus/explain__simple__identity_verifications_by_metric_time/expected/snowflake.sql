-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  metric_time__day
  , SUM(__identity_verifications) AS identity_verifications
FROM (
  -- Read Elements From Semantic Model 'id_verifications'
  -- Metric Time Dimension 'ds'
  -- Select: ['__identity_verifications', 'metric_time__day']
  -- Select: ['__identity_verifications', 'metric_time__day']
  SELECT
    DATE_TRUNC('day', ds) AS metric_time__day
    , 1 AS __identity_verifications
  FROM mf_corpus_2026_05_11_static.fct_id_verifications id_verifications_src_10000
) subq_3
GROUP BY
  metric_time__day
