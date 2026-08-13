-- Join Standard Outputs
-- Select: ['__bookings', 'listing__country_latest']
-- Select: ['__bookings', 'listing__country_latest']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  listings_latest_src_10000.country AS listing__country_latest
  , SUM(subq_1.__bookings) AS bookings
FROM (
  -- Read Elements From Semantic Model 'bookings_source'
  -- Metric Time Dimension 'ds'
  SELECT
    listing_id AS listing
    , 1 AS __bookings
  FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
) subq_1
LEFT OUTER JOIN
  mf_corpus_2026_05_11_static.dim_listings_latest listings_latest_src_10000
ON
  subq_1.listing = listings_latest_src_10000.listing_id
GROUP BY
  listing__country_latest
