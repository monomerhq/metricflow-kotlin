# Corpus integrity report

_Run time: 203.0s. Total checks: 508._

- PASS: 507 (99.8%)
- FAIL: 1
- ERROR: 0
- SKIP: 0

**Achieves 100% PASS target: NO**

## By subcommand

| subcommand | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|
| entities_for_metrics | 3 | 0 | 0 | 0 |
| explain | 413 | 0 | 0 | 0 |
| explain_get_dimension_values | 14 | 0 | 0 | 0 |
| list_dimensions | 19 | 0 | 0 | 0 |
| list_group_bys | 4 | 1 | 0 | 0 |
| list_metrics | 18 | 0 | 0 | 0 |
| list_saved_queries | 17 | 0 | 0 | 0 |
| validate_manifest | 19 | 0 | 0 | 0 |

## By dialect

| dialect | PASS | FAIL | ERROR | SKIP |
|---|---:|---:|---:|---:|
| (non-sql) | 80 | 1 | 0 | 0 |
| BigQuery | 61 | 0 | 0 | 0 |
| Databricks | 61 | 0 | 0 | 0 |
| DuckDB | 61 | 0 | 0 | 0 |
| Postgres | 61 | 0 | 0 | 0 |
| Redshift | 61 | 0 | 0 | 0 |
| Snowflake | 61 | 0 | 0 | 0 |
| Trino | 61 | 0 | 0 | 0 |

## FAIL rows

| case_id | dialect | diff_chars | diff_sample (first 80 chars after divergence) |
|---|---|---:|---|
| `list_group_bys__simple__views` |  | 0 | .entities[3].semantic_model_name: value mismatch expected='views_source' actual='listings_latest' |

## Proposed quarantines (awaiting evaluator approval)

These cases FAIL deterministically against their captured baseline but PASS on retry, meaning the oracle output ordering varies between runs. Per PROGRESS.md policy, this is category-2 quarantine (dialect-specific output order non-determinism, including sets/dicts). Builder must NOT commit `quarantine.md`; evaluator approves.

| case_id | proposed category | rationale |
|---|---|---|
| `list_group_bys__simple__views` | 2 (order non-determinism) | Re-run produced an alternative ordering that matched the baseline; the underlying `engine.list_*` API returns items via a set-iteration order that varies between Python processes. |

