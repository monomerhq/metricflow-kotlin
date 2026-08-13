-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  listing
  , SUM(__views) AS views
FROM (
  -- Read Elements From Semantic Model 'views_source'
  -- Metric Time Dimension 'ds'
  -- Select: ['__views', 'listing']
  -- Select: ['__views', 'listing']
  SELECT
    listing_id AS listing
    , 1 AS __views
  FROM mf_corpus_2026_05_11_static.fct_views views_source_src_10000
) subq_3
GROUP BY
  listing
