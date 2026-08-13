-- Join Standard Outputs
-- Select: ['__bookings', 'listing__country_latest']
-- Select: ['__bookings', 'listing__country_latest']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Select: ['listing__country_latest']
-- Write to DataTable
SELECT
  listings_latest_src_10000.country AS listing__country_latest
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
LEFT OUTER JOIN
  mf_corpus_2026_05_11_static.dim_listings_latest listings_latest_src_10000
ON
  bookings_source_src_10000.listing_id = listings_latest_src_10000.listing_id
GROUP BY
  listing__country_latest
