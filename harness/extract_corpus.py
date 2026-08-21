"""Build the Phase 1b differential-test corpus.

We use a **self-snapshotting** strategy:

1. Convert YAML manifests under
   ``python_oracle/upstream/metricflow_semantics/test_helpers/semantic_manifest_yamls/``
   into JSON envelopes the oracle CLI ingests (`corpus/manifests/*.json`).
2. For each manifest, define a curated set of (subcommand, args, dialect)
   triples. The args mirror the structure of the upstream integration tests.
3. Run the oracle once per triple; capture its output as the expected
   baseline. The harness re-runs and compares against this frozen output.

This is reproducible: re-running ``extract_corpus.py`` reproduces the same
corpus exactly because both the oracle and the manifest converter are
deterministic.

Why self-snapshotting and not "reuse upstream snapshots":

* Upstream snapshots use a session-random source schema rewritten to `*`'s.
* Upstream snapshots come from ``DataflowToSqlPlanConverter`` directly, not
  ``MetricFlowEngine.explain`` -- subtle differences in optimizer level.
* Upstream snapshots use ``IdNumberSpace`` to skew subquery aliases (e.g.
  ``subq_10`` instead of our ``subq_3``).
* Our Phase 3 Kotlin port has to match the Python *oracle's* output (which is
  what gRPC will return), not the Python *test snapshots*.

The corpus extractor never mutates ``python_oracle/upstream/`` or
``python_oracle/oracle/``; it only writes files under ``corpus/``.
"""

from __future__ import annotations

import json
import pathlib
import subprocess
import sys
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

ROOT = pathlib.Path(__file__).resolve().parents[1]
# Allow ``harness.X`` imports even when this script is invoked directly with
# the venv interpreter (no ``-m``).
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
ORACLE_CLI = ROOT / "python_oracle" / "cli.py"
ORACLE_PY = ROOT / "python_oracle" / ".venv" / "bin" / "python"
MANIFEST_YAML_ROOT = (
    ROOT
    / "python_oracle"
    / "upstream"
    / "metricflow_semantics"
    / "test_helpers"
    / "semantic_manifest_yamls"
)
CORPUS = ROOT / "corpus"
MANIFEST_OUT = CORPUS / "manifests"

# Match the seven supported dialects.
DIALECTS_SQL = ("Trino", "BigQuery", "Snowflake", "Databricks", "Redshift", "DuckDB", "Postgres")
DIALECT_TO_ENVELOPE = {
    "Trino": "TRINO",
    "BigQuery": "BIGQUERY",
    "Snowflake": "SNOWFLAKE",
    "Databricks": "DATABRICKS",
    "Redshift": "REDSHIFT",
    "DuckDB": "DUCKDB",
    "Postgres": "POSTGRES",
}


@dataclass(frozen=True)
class CorpusCase:
    """One case to extract.

    Attributes:
        case_id: directory name under ``corpus/``. Follows
            ``<subcommand>__<manifest_short>__<test_name>[__<dialect>]``.
        subcommand: one of the oracle subcommand names.
        manifest_name: directory name in ``corpus/manifests/`` (without ``.json``).
        args: the ``args`` dict to send to the oracle.
        dialects: tuple of dialect names; one ``expected/<dialect>.sql`` is
            written for each. Empty tuple means a non-SQL subcommand
            (``expected.json`` is written instead).
        source_test: human-readable pointer to where this case was inspired.
        notes: optional note for the meta file.
    """

    case_id: str
    subcommand: str
    manifest_name: str
    args: Dict[str, Any] = field(default_factory=dict)
    dialects: Tuple[str, ...] = ()
    source_test: str = ""
    notes: str = ""


def _run_oracle(subcommand: str, payload: Dict[str, Any]) -> Tuple[int, str, str]:
    """Call the oracle CLI with ``payload`` on stdin; return (rc, stdout, stderr)."""
    proc = subprocess.run(
        [str(ORACLE_PY), str(ORACLE_CLI), subcommand],
        input=json.dumps(payload),
        text=True,
        capture_output=True,
        cwd=str(ROOT),
        timeout=60,
    )
    return proc.returncode, proc.stdout, proc.stderr


def _load_manifest_json(manifest_name: str) -> Dict[str, Any]:
    path = MANIFEST_OUT / f"{manifest_name}.json"
    return json.loads(path.read_text())


def extract_one(case: CorpusCase) -> Tuple[str, str]:
    """Run the oracle for ``case`` and write its corpus directory.

    Returns ``(status, detail)`` where status is one of:
        OK_SQL   -- wrote one or more dialect SQL files
        OK_JSON  -- wrote expected.json
        SKIP     -- skipped (e.g. oracle error)
        FAIL     -- couldn't write outputs (I/O error, JSON-parse fail, ...)
    """
    case_dir = CORPUS / case.case_id
    case_dir.mkdir(parents=True, exist_ok=True)

    manifest = _load_manifest_json(case.manifest_name)

    # Build the input envelope.
    request_payload: Dict[str, Any] = dict(manifest)
    request_payload["args"] = case.args

    if case.dialects:
        wrote_any = False
        dialect_set: List[str] = []
        for dialect in case.dialects:
            payload = dict(request_payload)
            payload["sql_engine"] = DIALECT_TO_ENVELOPE[dialect]
            rc, out, err = _run_oracle(case.subcommand, payload)
            if rc != 0 or not out.strip():
                # Skip this dialect; record the error for the meta file.
                _write_meta(
                    case_dir,
                    case,
                    sql_dialect_set=dialect_set,
                    skip_reason=f"oracle non-zero for {dialect}: {err.strip()[:300]}",
                )
                if not wrote_any:
                    return ("SKIP", f"{case.case_id}: oracle error for {dialect}: {err.strip()[:200]}")
                continue
            try:
                doc = json.loads(out)
            except json.JSONDecodeError as e:
                return ("FAIL", f"{case.case_id}: bad JSON from oracle ({dialect}): {e}")
            sql = doc.get("sql")
            if not isinstance(sql, str):
                return ("FAIL", f"{case.case_id}: no SQL in oracle output for {dialect}")
            expected_dir = case_dir / "expected"
            expected_dir.mkdir(exist_ok=True)
            (expected_dir / f"{dialect.lower()}.sql").write_text(sql + ("\n" if not sql.endswith("\n") else ""))
            wrote_any = True
            dialect_set.append(dialect)

        if not wrote_any:
            return ("SKIP", f"{case.case_id}: no dialect produced output")

        _write_request(case_dir, request_payload)
        _write_meta(case_dir, case, sql_dialect_set=dialect_set)
        return ("OK_SQL", f"{case.case_id}: {len(dialect_set)} dialects")

    # Non-SQL subcommand path.
    rc, out, err = _run_oracle(case.subcommand, request_payload)
    if rc != 0 or not out.strip():
        return ("SKIP", f"{case.case_id}: oracle error: {err.strip()[:200]}")
    try:
        doc = json.loads(out)
    except json.JSONDecodeError as e:
        return ("FAIL", f"{case.case_id}: bad JSON from oracle: {e}")
    (case_dir / "expected.json").write_text(json.dumps(doc, indent=2, sort_keys=False) + "\n")
    _write_request(case_dir, request_payload)
    _write_meta(case_dir, case, sql_dialect_set=[])
    return ("OK_JSON", case.case_id)


def _write_request(case_dir: pathlib.Path, request_payload: Dict[str, Any]) -> None:
    """Persist the request envelope WITHOUT ``sql_engine`` (dialect is meta).

    The runner adds the dialect when needed. Keeping the request dialect-free
    lets one ``request.json`` cover all dialects for an explain case.
    """
    payload = dict(request_payload)
    payload.pop("sql_engine", None)
    (case_dir / "request.json").write_text(json.dumps(payload, indent=2, sort_keys=False))


def _write_meta(
    case_dir: pathlib.Path,
    case: CorpusCase,
    *,
    sql_dialect_set: List[str],
    skip_reason: Optional[str] = None,
) -> None:
    meta = {
        "case_id": case.case_id,
        "subcommand": case.subcommand,
        "manifest_id": case.manifest_name,
        "args": case.args,
        "dialect_set": sql_dialect_set,
        "source_test": case.source_test,
        "notes": case.notes,
    }
    if skip_reason:
        meta["skip_reason"] = skip_reason
    (case_dir / "meta.json").write_text(json.dumps(meta, indent=2, sort_keys=False) + "\n")


# --- case definitions --------------------------------------------------------

# `simple_manifest` based explain cases, mirroring topics covered by
# `tests_metricflow/integration/test_*` and `tests_metricflow/query_rendering/`.
# Args are reconstructed from upstream test bodies; the case_id encodes the
# upstream test as ``__test_<name>`` for traceability.

SIMPLE_MANIFEST_EXPLAIN: Tuple[CorpusCase, ...] = (
    CorpusCase(
        case_id="explain__simple__bookings_by_metric_time__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/integration/test_rendered_query.py::test_render_query",
        notes="The canonical metricflow 'simple metric over metric_time' query.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_listings_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings", "listings"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/integration/test_rendered_query.py::test_id_enumeration",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_no_groupby",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"]},
        dialects=DIALECTS_SQL,
        source_test="derived (no group_by)",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_listing",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["listing"]},
        dialects=DIALECTS_SQL,
        source_test="derived (entity group_by)",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_listing_country",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["listing__country_latest"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_query_rendering.py::test_local_dimension_using_local_entity (variant)",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_metric_time_with_limit",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "limit": 10,
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_query_rendering.py::test_limit_rows",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_metric_time_with_order",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "order_by_names": ["-metric_time__day"],
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__instant_bookings_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["instant_bookings"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Metric with filter '{{ Dimension('booking__is_instant') }}'.",
    ),
    CorpusCase(
        case_id="explain__simple__average_booking_value",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["average_booking_value"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Average aggregation.",
    ),
    CorpusCase(
        case_id="explain__simple__max_min_booking_value",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["max_booking_value", "min_booking_value"],
            "group_by_names": ["metric_time__day"],
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookers_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookers"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Count-distinct aggregation.",
    ),
    CorpusCase(
        case_id="explain__simple__views_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["views"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__views_by_listing",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["views"], "group_by_names": ["listing"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_booking_is_instant",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["booking__is_instant"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_order_output_columns_by_input_order",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "order_output_columns_by_input_order": True,
        },
        dialects=DIALECTS_SQL,
        notes="order_output_columns_by_input_order=True is set by integration tests.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_listings_views_multi",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings", "listings", "views"],
            "group_by_names": ["metric_time__day"],
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_ds_week",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__week"]},
        dialects=DIALECTS_SQL,
        notes="Week-granularity dimension.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_ds_month",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__month"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_ds_quarter",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__quarter"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_ds_year",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__year"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_with_time_constraint",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "time_constraint_start": "2020-01-01",
            "time_constraint_end": "2020-01-31",
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_with_where",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "where_constraints": ["{{ Dimension('booking__is_instant') }} = true"],
        },
        dialects=DIALECTS_SQL,
    ),
    # ---- Additional simple-manifest explain cases ----
    CorpusCase(
        case_id="explain__simple__revenue_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["revenue"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__identity_verifications_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["identity_verifications"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_per_listing_ratio",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_per_listing"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Ratio with cross-semantic-model join.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_per_dollar_ratio",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_per_dollar"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__booking_fees_derived",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["booking_fees"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Single-measure derived metric (expr).",
    ),
    CorpusCase(
        case_id="explain__simple__booking_fees_per_booker_derived",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["booking_fees_per_booker"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Multi-measure derived metric.",
    ),
    CorpusCase(
        case_id="explain__simple__views_times_booking_value_derived",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["views_times_booking_value"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Cross-semantic-model derived metric.",
    ),
    CorpusCase(
        case_id="explain__simple__instant_booking_value_filter_metric",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["instant_booking_value"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Simple metric with `filter` on the metric definition.",
    ),
    CorpusCase(
        case_id="explain__simple__average_instant_booking_value",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["average_instant_booking_value"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Average aggregation + metric-level filter.",
    ),
    CorpusCase(
        case_id="explain__simple__lux_listings_filter_metric",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["lux_listings"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Filter metric depending on a different semantic model's dimension.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_metric_time_year",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__year", "listing__country_latest"],
        },
        dialects=DIALECTS_SQL,
        notes="Multi-group-by (time + categorical).",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_listings_views_by_country",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings", "listings", "views"],
            "group_by_names": ["listing__country_latest"],
        },
        dialects=DIALECTS_SQL,
        notes="Three metrics by listing country.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_per_view_ratio_by_country",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings_per_view"],
            "group_by_names": ["listing__country_latest"],
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__bookings_saved_query",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"saved_query_name": "p0_booking"},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/engine/test_explain.py::test_concurrent_explain_consistency",
        notes="Saved query path (no explicit metric_names/group_by_names).",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_with_listing_country_where",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "where_constraints": ["{{ Dimension('listing__country_latest') }} = 'us'"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_query_rendering.py::test_filter_with_where_constraint_on_join_dim",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_metric_time_hour",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__hour"]},
        dialects=DIALECTS_SQL,
        notes="Sub-day granularity should use the hour time-spine.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_with_limit_and_order",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__day"],
            "order_by_names": ["-bookings"],
            "limit": 5,
        },
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__simple__booking_value_for_non_null_listing_id",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["booking_value_for_non_null_listing_id"]},
        dialects=DIALECTS_SQL,
        notes="Filter expression with `IS NOT NULL` predicate.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_two_dimensions",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["booking__is_instant", "listing__country_latest"],
        },
        dialects=DIALECTS_SQL,
        notes="Two non-time dimensions.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_user_home_state",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["identity_verifications"],
            "group_by_names": ["user__home_state"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_query_rendering.py::test_partitioned_join",
    ),
    CorpusCase(
        case_id="explain__simple__booking_payments_by_metric_time",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["booking_payments"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Measure agg_time_dimension different from default.",
    ),
)


# Runtime paths that require a configured time spine. These are deliberately
# separate from the broad explain corpus: they are the acceptance matrix for
# the upstream 0.210.0 time-spine port and must pass on every supported SQL
# dialect before the port is considered complete.
TIME_SPINE_RUNTIME_EXPLAIN: Tuple[CorpusCase, ...] = (
    CorpusCase(
        case_id="explain__simple__bookings_join_to_time_spine__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_join_to_time_spine"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_time_spine_join_rendering.py",
        notes="Simple metric whose measure explicitly joins to the daily time spine.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_month_with_start_only__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__month"],
            "time_constraint_start": "2020-01-15",
        },
        dialects=DIALECTS_SQL,
        source_test="metricflow_semantics/query/query_parser.py::_adjust_time_constraint",
        notes="A start-only range fills the all-time end and expands the start to the month boundary.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_by_month_with_end_only__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings"],
            "group_by_names": ["metric_time__month"],
            "time_constraint_end": "2020-02-15",
        },
        dialects=DIALECTS_SQL,
        source_test="metricflow_semantics/query/query_parser.py::_adjust_time_constraint",
        notes="An end-only range fills the all-time start and expands the end to the month boundary.",
    ),
    CorpusCase(
        case_id="explain__simple__trailing_2_months_revenue__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["trailing_2_months_revenue"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric",
        notes="Cumulative metric with a two-month rolling window.",
    ),
    CorpusCase(
        case_id="explain__simple__trailing_2_months_revenue_with_time_constraint__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["trailing_2_months_revenue"],
            "group_by_names": ["metric_time__day"],
            "time_constraint_start": "2020-01-01",
            "time_constraint_end": "2020-01-01",
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_with_time_constraint",
        notes="Rolling cumulative source range expands, then the requested range is restored.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_all_time__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_all_time"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py",
        notes="Unbounded cumulative metric.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_all_time_with_time_constraint__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings_all_time"],
            "group_by_names": ["metric_time__day"],
            "time_constraint_start": "2020-01-01",
            "time_constraint_end": "2020-01-01",
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_no_window_with_time_constraint",
        notes="Unbounded cumulative source starts at all-time begin, then narrows to the requested day.",
    ),
    CorpusCase(
        case_id="explain__simple__visit_buy_conversion_rate_7days__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["visit_buy_conversion_rate_7days"],
            "group_by_names": ["metric_time__day"],
            "where_constraints": ["{{ TimeDimension('metric_time', 'day') }} = '2020-01-01'"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_conversion_metric_rendering.py::test_conversion_metric_with_window",
        notes="Conversion metric with a seven-day conversion window.",
    ),
    CorpusCase(
        case_id="explain__simple__visit_buy_conversion_rate_7days_with_time_constraint__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["visit_buy_conversion_rate_7days"],
            "group_by_names": ["metric_time__day", "visit__referrer_id"],
            "where_constraints": ["{{ Dimension('visit__referrer_id') }} = 'ref_id_01'"],
            "time_constraint_start": "2020-01-01",
            "time_constraint_end": "2020-01-02",
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_conversion_metric_rendering.py::test_conversion_metric_with_window_and_time_constraint",
        notes="Conversion window with a categorical filter and bounded event range.",
    ),
    CorpusCase(
        case_id="explain__simple__visit_buy_conversion_rate_7days_with_filter_only__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["visit_buy_conversion_rate_7days"],
            "group_by_names": ["metric_time__day"],
            "where_constraints": ["{{ Dimension('visit__referrer_id') }} = 'ref_id_01'"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_conversion_metric_rendering.py",
        notes="The conversion base keeps a filter-only dimension through its first projection.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_growth_2_weeks__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_growth_2_weeks"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_derived_metric_with_offset_window",
        notes="Derived metric with an offset window.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_growth_2_weeks_with_time_constraint__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings_growth_2_weeks"],
            "group_by_names": ["metric_time__day"],
            "time_constraint_start": "2019-12-19",
            "time_constraint_end": "2020-01-02",
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_time_offset_metric_with_time_constraint",
        notes="Offset input is shifted before the original requested range is applied.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_growth_since_start_of_month__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings_growth_since_start_of_month"],
            "group_by_names": ["metric_time__day"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_derived_metric_rendering.py::test_derived_metric_with_offset_to_grain",
        notes="Derived metric offset to the start of its month.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_custom_alien_day__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "group_by_names": ["metric_time__alien_day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_custom_granularity.py::test_simple_metric_with_custom_granularity",
        notes="Custom time granularity supplied by the daily time spine.",
    ),
    CorpusCase(
        case_id="explain__simple__subdaily_join_multiple_time_spines__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["subdaily_join_to_time_spine_metric"],
            "group_by_names": ["metric_time__alien_day", "metric_time__hour"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_custom_granularity.py::test_multiple_time_spines_in_query_for_join_to_time_spine_metric",
        notes="One query selecting both an hourly spine and a custom grain from the daily spine.",
    ),
    CorpusCase(
        case_id="explain__simple__subdaily_cumulative_multiple_time_spines__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["subdaily_cumulative_window_metric"],
            "group_by_names": ["metric_time__alien_day", "metric_time__hour"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_custom_granularity.py::test_multiple_time_spines_in_query_for_cumulative_metric",
        notes="Sub-daily cumulative metric that requires multiple configured time spines.",
    ),
    CorpusCase(
        case_id="explain__simple__revenue_mtd_by_month__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["revenue_mtd"], "group_by_names": ["metric_time__month"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py::test_cumulative_metric_grain_to_date",
        notes="Month-to-date cumulative metric queried above its minimum daily grain.",
    ),
    CorpusCase(
        case_id="explain__simple__trailing_2_months_revenue_by_month__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["trailing_2_months_revenue"], "group_by_names": ["metric_time__month"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_cumulative_metric_rendering.py",
        notes="Rolling cumulative metric requiring window reaggregation at month grain.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_offset_one_alien_day_by_day__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_offset_one_alien_day"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_custom_granularity.py::test_custom_offset_window",
        notes="Custom-granularity offset window projected at the base day grain.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_offset_one_alien_day_by_alien_day__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["bookings_offset_one_alien_day"],
            "group_by_names": ["metric_time__alien_day", "booking__ds__alien_day"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_custom_granularity.py::test_custom_offset_window_with_only_window_grain",
        notes="Custom offset where the queried grains match the offset grain.",
    ),
    CorpusCase(
        case_id="explain__simple__subdaily_cumulative_grain_to_date_hour__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["subdaily_cumulative_grain_to_date_metric"],
            "group_by_names": ["metric_time__hour"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_cumulative_grain_to_date_metric",
        notes="Sub-daily grain-to-date cumulative metric.",
    ),
    CorpusCase(
        case_id="explain__simple__subdaily_offset_window_hour__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["subdaily_offset_window_metric"], "group_by_names": ["metric_time__hour"]},
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_offset_window_metric",
        notes="Sub-daily standard offset window.",
    ),
    CorpusCase(
        case_id="explain__simple__subdaily_offset_to_grain_hour__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["subdaily_offset_grain_to_date_metric"],
            "group_by_names": ["metric_time__hour"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_granularity_date_part_rendering.py::test_subdaily_offset_to_grain_metric",
        notes="Sub-daily offset-to-grain metric.",
    ),
    CorpusCase(
        case_id="explain__simple__visit_buy_conversion_rate_by_session__all_dialects",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={
            "metric_names": ["visit_buy_conversion_rate_by_session"],
            "group_by_names": ["visit__referrer_id", "metric_time__day"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/plan_conversion/dataflow_to_sql/test_conversion_metrics_to_sql.py::test_conversion_rate_with_constant_properties",
        notes="Conversion metric with a constant-property equality constraint.",
    ),
)


# Multi-hop join manifest cases.
MULTI_HOP_EXPLAIN: Tuple[CorpusCase, ...] = (
    CorpusCase(
        case_id="explain__multi_hop__txn_count_by_customer_name",
        subcommand="explain",
        manifest_name="multi_hop_join_manifest",
        args={
            "metric_names": ["txn_count"],
            "group_by_names": ["account_id__customer_id__customer_name"],
        },
        dialects=DIALECTS_SQL,
        source_test="tests_metricflow/query_rendering/test_query_rendering.py::test_multihop_node",
    ),
    CorpusCase(
        case_id="explain__multi_hop__txn_count_by_metric_time",
        subcommand="explain",
        manifest_name="multi_hop_join_manifest",
        args={"metric_names": ["txn_count"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
    ),
    CorpusCase(
        case_id="explain__multi_hop__txn_count_no_groupby",
        subcommand="explain",
        manifest_name="multi_hop_join_manifest",
        args={"metric_names": ["txn_count"]},
        dialects=DIALECTS_SQL,
    ),
)


# Derived metrics manifest cases.
DERIVED_METRICS_EXPLAIN: Tuple[CorpusCase, ...] = (
    CorpusCase(
        case_id="explain__derived_metrics__bookings_metric_level_0_index_0",
        subcommand="explain",
        manifest_name="derived_metrics_manifest",
        args={"metric_names": ["metric_level_0_index_0"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Derived-metric base case from derived_metrics_manifest.",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_per_booker_ratio",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_per_booker"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Ratio metric (bookings / bookers).",
    ),
    CorpusCase(
        case_id="explain__simple__bookings_per_view_ratio",
        subcommand="explain",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings_per_view"], "group_by_names": ["metric_time__day"]},
        dialects=DIALECTS_SQL,
        notes="Ratio metric (bookings / views).",
    ),
)


# `entities_for_metrics` / `list_*` cases for the same simple manifest.
SIMPLE_LIST_CASES: Tuple[CorpusCase, ...] = (
    CorpusCase(
        case_id="list_metrics__simple__with_dimensions",
        subcommand="list_metrics",
        manifest_name="simple_manifest",
        args={"include_dimensions": True},
        source_test="MetricFlowEngine.list_metrics(include_dimensions=True)",
    ),
    CorpusCase(
        case_id="list_metrics__simple__without_dimensions",
        subcommand="list_metrics",
        manifest_name="simple_manifest",
        args={"include_dimensions": False},
    ),
    CorpusCase(
        case_id="list_dimensions__simple__all",
        subcommand="list_dimensions",
        manifest_name="simple_manifest",
        args={},
    ),
    CorpusCase(
        case_id="list_dimensions__simple__for_bookings",
        subcommand="list_dimensions",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"]},
    ),
    CorpusCase(
        case_id="list_dimensions__simple__for_views",
        subcommand="list_dimensions",
        manifest_name="simple_manifest",
        args={"metric_names": ["views"]},
    ),
    CorpusCase(
        case_id="entities_for_metrics__simple__bookings",
        subcommand="entities_for_metrics",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"]},
    ),
    CorpusCase(
        case_id="entities_for_metrics__simple__bookings_views",
        subcommand="entities_for_metrics",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings", "views"]},
    ),
    CorpusCase(
        case_id="list_group_bys__simple__bookings",
        subcommand="list_group_bys",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"]},
    ),
    CorpusCase(
        case_id="list_group_bys__simple__views",
        subcommand="list_group_bys",
        manifest_name="simple_manifest",
        args={"metric_names": ["views"]},
    ),
    CorpusCase(
        case_id="list_group_bys__simple__bookings_dunder_order",
        subcommand="list_group_bys",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "order_by": "DUNDER_NAME"},
    ),
    CorpusCase(
        case_id="list_group_bys__simple__bookings_semantic_model_order",
        subcommand="list_group_bys",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "order_by": "SEMANTIC_MODEL_NAME"},
    ),
    CorpusCase(
        case_id="list_saved_queries__simple",
        subcommand="list_saved_queries",
        manifest_name="simple_manifest",
        args={},
    ),
    CorpusCase(
        case_id="explain_get_dimension_values__simple__listing_country_for_bookings",
        subcommand="explain_get_dimension_values",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "get_group_by_values": "listing__country_latest"},
        dialects=DIALECTS_SQL,
        source_test="MetricFlowEngine.explain_get_dimension_values",
    ),
    CorpusCase(
        case_id="explain_get_dimension_values__simple__metric_time_for_bookings",
        subcommand="explain_get_dimension_values",
        manifest_name="simple_manifest",
        args={"metric_names": ["bookings"], "get_group_by_values": "metric_time__day"},
        dialects=DIALECTS_SQL,
    ),
)


# Other manifests: validate_manifest + a few `list_*` calls per manifest. This
# gives us coverage of every YAML manifest so the corpus catches regressions
# in any specific feature.

OTHER_MANIFEST_NAMES: Tuple[str, ...] = (
    "ambiguous_resolution_manifest",
    "cyclic_join_manifest",
    "data_warehouse_validation_manifest",
    "derived_metrics_manifest",
    "extended_date_manifest",
    "join_types_manifest",
    "multi_hop_join_manifest",
    "name_edge_case_manifest",
    "non_sm_manifest",
    "partitioned_multi_hop_join_manifest",
    "scd_manifest",
    "sg_00_minimal_manifest",
    "sg_02_single_join",
    "sg_05_derived_metric",
    "simple_manifest",
    "simple_multi_hop_join_manifest",
)


# The config_linter manifest is intentionally invalid -- it's used by upstream
# to test that validation rules catch known issues. We extract only the
# validate_manifest case for it (the other commands would crash on a manifest
# that fails to build a working engine).
CONFIG_LINTER_VALIDATE: CorpusCase = CorpusCase(
    case_id="validate_manifest__config_linter_manifest_invalid",
    subcommand="validate_manifest",
    manifest_name="config_linter_manifest",
    args={},
    source_test="metricflow_semantics/test_helpers/semantic_manifest_yamls/config_linter_manifest/* (intentional errors)",
    notes="Expected to produce blocking errors. Validates that we correctly surface intentional validation failures.",
)


def _per_manifest_validate_and_lists() -> Tuple[CorpusCase, ...]:
    out: List[CorpusCase] = []
    for name in OTHER_MANIFEST_NAMES:
        out.append(
            CorpusCase(
                case_id=f"validate_manifest__{name}",
                subcommand="validate_manifest",
                manifest_name=name,
                args={},
                source_test="SemanticManifestValidator on canonical YAML manifest",
            )
        )
        out.append(
            CorpusCase(
                case_id=f"list_metrics__{name}__with_dimensions",
                subcommand="list_metrics",
                manifest_name=name,
                args={"include_dimensions": True},
            )
        )
        out.append(
            CorpusCase(
                case_id=f"list_dimensions__{name}__all",
                subcommand="list_dimensions",
                manifest_name=name,
                args={},
            )
        )
        out.append(
            CorpusCase(
                case_id=f"list_saved_queries__{name}",
                subcommand="list_saved_queries",
                manifest_name=name,
                args={},
            )
        )
    return tuple(out)


# Also include the canonical minimal valid manifest fixture so we have a known-
# stable baseline that doesn't depend on YAML conversion.
def _minimal_fixture_cases() -> Tuple[CorpusCase, ...]:
    # We won't re-generate this manifest; we copy it into corpus/manifests/.
    return (
        CorpusCase(
            case_id="explain__minimal_fixture__bookings_by_metric_time",
            subcommand="explain",
            manifest_name="minimal_valid_manifest",
            args={"metric_names": ["bookings"], "group_by_names": ["metric_time__day"]},
            dialects=DIALECTS_SQL,
            source_test="python_oracle/tests/fixtures/minimal_valid_manifest.json",
            notes="The same fixture used by oracle smoke tests; serves as a stable baseline.",
        ),
        CorpusCase(
            case_id="validate_manifest__minimal_fixture",
            subcommand="validate_manifest",
            manifest_name="minimal_valid_manifest",
            args={},
        ),
        CorpusCase(
            case_id="validate_manifest__minimal_invalid_fixture",
            subcommand="validate_manifest",
            manifest_name="minimal_invalid_manifest",
            args={},
            notes="Expected to produce blocking validation errors.",
        ),
    )


def _multi_hop_list_cases() -> Tuple[CorpusCase, ...]:
    """A few extra list cases on the multi-hop manifest to exercise joins via `list_group_bys`."""
    return (
        CorpusCase(
            case_id="list_group_bys__simple_multi_hop_join__txn_count",
            subcommand="list_group_bys",
            manifest_name="multi_hop_join_manifest",
            args={"metric_names": ["txn_count"]},
        ),
        CorpusCase(
            case_id="entities_for_metrics__simple_multi_hop_join__txn_count",
            subcommand="entities_for_metrics",
            manifest_name="multi_hop_join_manifest",
            args={"metric_names": ["txn_count"]},
        ),
    )


def all_cases() -> Tuple[CorpusCase, ...]:
    return (
        SIMPLE_MANIFEST_EXPLAIN
        + TIME_SPINE_RUNTIME_EXPLAIN
        + MULTI_HOP_EXPLAIN
        + DERIVED_METRICS_EXPLAIN
        + SIMPLE_LIST_CASES
        + _multi_hop_list_cases()
        + _minimal_fixture_cases()
        + _per_manifest_validate_and_lists()
        + (CONFIG_LINTER_VALIDATE,)
    )


# --- driver ------------------------------------------------------------------


def _convert_all_manifests() -> List[str]:
    """Convert every YAML manifest under ``MANIFEST_YAML_ROOT`` to JSON.

    Also copies the canonical fixtures shipped with the oracle.
    """
    from harness.manifest_loader import iter_manifest_dirs, write_manifest_json

    MANIFEST_OUT.mkdir(parents=True, exist_ok=True)
    names: List[str] = []
    for d in iter_manifest_dirs(MANIFEST_YAML_ROOT):
        write_manifest_json(d, MANIFEST_OUT / f"{d.name}.json")
        names.append(d.name)

    # Copy the JSON fixtures used by oracle smoke tests so they're addressable
    # by ``manifest_name``.
    fixtures_dir = ROOT / "python_oracle" / "tests" / "fixtures"
    for fix in ("minimal_valid_manifest", "minimal_invalid_manifest"):
        src = fixtures_dir / f"{fix}.json"
        if src.is_file():
            dest = MANIFEST_OUT / f"{fix}.json"
            dest.write_text(src.read_text())
            names.append(fix)
    return names


def _write_index(cases: Tuple[CorpusCase, ...], results: List[Tuple[str, str]]) -> None:
    lines = [
        "# Corpus index",
        "",
        f"Total cases extracted: {sum(1 for s, _ in results if s.startswith('OK'))}",
        f"Skipped: {sum(1 for s, _ in results if s == 'SKIP')}",
        f"Failed: {sum(1 for s, _ in results if s == 'FAIL')}",
        "",
        "| case_id | subcommand | manifest | dialects | source_test |",
        "|---|---|---|---|---|",
    ]
    for case in cases:
        d = ",".join(case.dialects) if case.dialects else "-"
        src = case.source_test or "-"
        lines.append(f"| `{case.case_id}` | `{case.subcommand}` | `{case.manifest_name}` | {d} | {src} |")
    lines.append("")
    (CORPUS / "INDEX.md").write_text("\n".join(lines))


def main() -> int:
    print("Phase 1b corpus extractor", file=sys.stderr)
    print(f"Repo root: {ROOT}", file=sys.stderr)
    print("Converting YAML manifests ...", file=sys.stderr)
    names = _convert_all_manifests()
    print(f"  wrote {len(names)} manifest envelopes -> {MANIFEST_OUT}", file=sys.stderr)

    cases = all_cases()
    print(f"Extracting {len(cases)} corpus cases ...", file=sys.stderr)

    results: List[Tuple[str, str]] = []
    for i, case in enumerate(cases, 1):
        status, detail = extract_one(case)
        results.append((status, detail))
        flag = {"OK_SQL": ".", "OK_JSON": ".", "SKIP": "S", "FAIL": "F"}.get(status, "?")
        sys.stderr.write(flag)
        if i % 80 == 0:
            sys.stderr.write(f" {i}/{len(cases)}\n")
        sys.stderr.flush()
    sys.stderr.write(f" done ({len(cases)}/{len(cases)})\n")

    _write_index(cases, results)

    total = len(results)
    ok = sum(1 for s, _ in results if s.startswith("OK"))
    skip = sum(1 for s, _ in results if s == "SKIP")
    fail = sum(1 for s, _ in results if s == "FAIL")
    print(f"\nExtracted {ok}/{total} cases (skipped {skip}, failed {fail}).", file=sys.stderr)
    print("\nSkips:", file=sys.stderr)
    for status, detail in results:
        if status == "SKIP":
            print(f"  - {detail}", file=sys.stderr)
    print("\nFailures:", file=sys.stderr)
    for status, detail in results:
        if status == "FAIL":
            print(f"  - {detail}", file=sys.stderr)
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
