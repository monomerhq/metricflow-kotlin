# harness/

Differential-test harness for metricflow-kotlin. All Python; uses the
`python_oracle/.venv/` interpreter exclusively (no second venv). The harness
**only** talks to the oracle through its CLI contract
(`python_oracle/cli.py <subcommand>`) — that's the same contract Phase 3
Kotlin will mirror.

## Files

* **`extract_corpus.py`** — one-time extractor that converts upstream YAML
  manifests to JSON envelopes and runs the oracle once per case to capture
  expected output. Reproducible.
* **`manifest_loader.py`** — helper used by `extract_corpus.py`. Reads a
  manifest YAML directory (via the upstream parser, with `$source_schema`
  substituted to a stable 27-char identifier) and dumps a JSON envelope.
* **`run_oracle.py`** — re-runs the oracle on every corpus case and compares
  outputs (with `sql_norm` applied). Emits CSV + Markdown reports under
  `reports/`.
* **`sql_norm/`** — conservative SQL normalizer (3 seed rules). See
  [`sql_norm/README.md`](sql_norm/README.md).
* **`reports/`** — output of the latest `run_oracle.py`. The Markdown report
  is committed for reference; the CSV is reproducible.
* **`EXTRACTION_NOTES.md`** — what we learned about metricflow's test fixture
  machinery while building this. Read before extending the corpus.

## Usage

### Re-extract the corpus from scratch

```bash
rm -rf corpus/*/             # keep corpus/manifests/, drop case directories
python_oracle/.venv/bin/python harness/extract_corpus.py
```

Reads YAMLs from
`python_oracle/upstream/metricflow_semantics/test_helpers/semantic_manifest_yamls/`,
runs the oracle once per case, writes `corpus/<case-id>/`.

### Re-run integrity check (the main developer loop)

```bash
python_oracle/.venv/bin/python harness/run_oracle.py
```

Writes `harness/reports/corpus_integrity.csv` and `harness/reports/corpus_integrity.md`.
Goal: ≥80% PASS during Phase 1b; ≥100% (modulo evaluator-approved quarantines)
from Phase 2 onward.

### Add a normalizer rule

See `sql_norm/README.md`. Must be motivated by ≥3 corpus cases (waived for
Phase 1b seed rules only).

## Design notes

### Why self-snapshot instead of reusing upstream snapshots?

The oracle runs `MetricFlowEngine.explain` directly. Upstream snapshots in
`tests_metricflow/snapshots/` come from two different code paths
(`MetricFlowEngine.explain` for `test_rendered_query.py`; the
`DataflowToSqlPlanConverter` for the larger `test_query_rendering.py` family)
and use a shared `IdNumberSpace` that bumps subquery alias counters per test.
Matching upstream snapshots byte-for-byte would require either fork-and-skew
the IdNumberSpace or invest in alias renumbering normalization. Self-snapshotting
gives Phase 3 Kotlin a clean target: match the **oracle's** output.

### Why a SQL string normalizer and not a SQL parser/AST diff?

Phase 1b's bar is conservative: cosmetic differences only (line endings,
trailing whitespace, blank-line runs). Anything more invasive — alias
canonicalization, schema masking, comment stripping — risks masking real
divergences and gets added rule-by-rule with explicit evidence. See
`sql_norm/README.md`.

### Determinism guarantees

* `manifest_loader.py` is fully deterministic given the same YAML inputs
  (upstream parser + pydantic v1 dump is order-stable for our purposes).
* `extract_corpus.py` is deterministic for SQL outputs (the oracle is
  deterministic).
* A small subset of `list_group_bys` cases (where an entity appears under
  multiple semantic models reachable by the same query) shows
  **set-iteration order** non-determinism. These are surfaced as **category-2
  quarantine candidates** in the integrity report and require evaluator
  approval before any `quarantine.md` is committed.

### What the harness does NOT do

* Does not run Kotlin. That's Phase 3.
* Does not generate corpus from the integration test YAMLs
  (`tests_metricflow/integration/test_cases/*.yaml`) — those tests run end-to-end
  with a real DW and check result tables, not SQL strings. Extracting them
  yields inputs without ground-truth SQL.
* Does not parse or evaluate SQL — string normalization only.
