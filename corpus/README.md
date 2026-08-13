# corpus/

Differential-test corpus for metricflow-kotlin. Each subdirectory is one test
case. Phase 1b builds this corpus from the canonical metricflow YAML manifests
plus a curated set of subcommand args.

## Layout

```
corpus/
├── README.md                       (this file)
├── INDEX.md                        generated index of all cases
├── manifests/                      JSON envelopes loaded into the oracle CLI
│   ├── simple_manifest.json
│   ├── simple_multi_hop_join_manifest.json
│   ├── ambiguous_resolution_manifest.json
│   ├── ...                         (one per upstream YAML manifest + the minimal_valid_manifest fixtures)
└── <case-id>/
    ├── request.json                envelope ready to pipe to the oracle CLI (no `sql_engine` — added by runner)
    ├── meta.json                   subcommand, manifest_id, args, dialect_set, source_test, notes
    ├── expected/                   one per dialect, for SQL-generating subcommands
    │   ├── trino.sql
    │   ├── bigquery.sql
    │   ├── snowflake.sql
    │   ├── databricks.sql
    │   ├── redshift.sql
    │   ├── duckdb.sql
    │   └── postgres.sql
    └── expected.json               for non-SQL subcommands (validate_manifest, list_*, entities_for_metrics, list_saved_queries)
```

## Naming convention

`<subcommand>__<manifest_short>__<test_name>[__<dialect_set>]`

Examples:

* `explain__simple__bookings_by_metric_time__all_dialects`
* `validate_manifest__simple_manifest`
* `list_group_bys__simple__bookings_dunder_order`

`<manifest_short>` is the manifest directory name with the `_manifest` suffix
stripped where convenient (e.g. `simple_manifest` → `simple`).

## Adding a new case manually

1. Pick a JSON manifest under `manifests/` (or convert a new YAML manifest with
   `python_oracle/.venv/bin/python harness/manifest_loader.py <yaml_dir> <out_json>`).
2. Create a directory `corpus/<case-id>/`.
3. Write `request.json`:
   ```json
   {
     "semantic_models": [...],
     "metrics": [...],
     "project_configuration": {...},
     "saved_queries": [...],
     "args": { ... }
   }
   ```
   Copy the manifest fields verbatim from `manifests/<name>.json` and add the
   subcommand-specific `args` block (see `python_oracle/cli/SCHEMA.md`).
4. Write `meta.json`:
   ```json
   {
     "case_id": "<same as dir name>",
     "subcommand": "explain",
     "manifest_id": "simple_manifest",
     "args": { ... },
     "dialect_set": ["Trino", "BigQuery", ...],
     "source_test": "tests_metricflow/...::name",
     "notes": ""
   }
   ```
   `dialect_set` is empty (`[]`) for non-SQL subcommands.
5. Capture expected output: pipe `request.json` plus a `sql_engine` field to
   the oracle CLI per dialect, save outputs as `expected/<dialect>.sql` or
   `expected.json`.
6. Run `python_oracle/.venv/bin/python harness/run_oracle.py` and confirm the
   new case PASSes.

## Regenerating from extraction

The extractor at `harness/extract_corpus.py`:

* Converts every YAML manifest under
  `python_oracle/upstream/metricflow_semantics/test_helpers/semantic_manifest_yamls/`
  to a JSON envelope.
* Runs the oracle for every entry in `harness/extract_corpus.py::all_cases()`.
* Writes corpus directories + `corpus/INDEX.md`.

```bash
rm -rf corpus/*/                                   # nuke previous cases (keeps manifests/)
python_oracle/.venv/bin/python harness/extract_corpus.py
```

This is deterministic — the oracle is deterministic and the manifest
converter is deterministic. Re-running with no changes produces byte-identical
corpus files (modulo set-order non-determinism in 1 `list_group_bys` case;
see `harness/reports/corpus_integrity.md` for the quarantine candidate).

## What the corpus does NOT include

* No SQL execution results. The oracle (and Phase 3 Kotlin) is a SQL generator,
  not an executor. We diff generated SQL strings, not query results.
* No upstream snapshot files. We snapshot the **oracle's** output, not
  `tests_metricflow/snapshots/`. The two are similar but not byte-identical
  (subquery alias counters differ because upstream uses a shared `IdNumberSpace`).
* No data-warehouse credentials. The oracle's `OracleSqlClient` does not execute.

## Source attribution

Every case carries a `meta.json::source_test` field pointing back to the
upstream test that inspired the (subcommand, args) shape, where applicable.
"""
