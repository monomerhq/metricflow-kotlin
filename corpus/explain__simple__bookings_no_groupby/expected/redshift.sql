-- Read Elements From Semantic Model 'bookings_source'
-- Metric Time Dimension 'ds'
-- Select: ['__bookings']
-- Select: ['__bookings']
-- Aggregate Inputs for Simple Metrics
-- Compute Metrics via Expressions
-- Write to DataTable
SELECT
  SUM(1) AS bookings
FROM mf_corpus_2026_05_11_static.fct_bookings bookings_source_src_10000
