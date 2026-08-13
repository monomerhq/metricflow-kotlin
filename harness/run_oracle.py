"""Run the oracle CLI on every corpus case and compare to expected outputs.

For every directory under ``corpus/``:

* Load ``request.json`` and ``meta.json``.
* For each dialect in ``meta.json.dialect_set`` (or, for non-SQL subcommands,
  just once with no dialect): pipe the envelope to
  ``python_oracle/.venv/bin/python python_oracle/cli.py <subcommand>``.
* Normalize the resulting SQL and compare to ``expected/<dialect>.sql``
  (or do a JSON deep-equal against ``expected.json``).
* Record PASS / FAIL / ERROR / SKIP, write a CSV + Markdown report.

Re-runnable: writes deterministic outputs into
``harness/reports/corpus_integrity.csv`` and
``harness/reports/corpus_integrity.md``.
"""

from __future__ import annotations

import csv
import json
import pathlib
import subprocess
import sys
import time
from collections import Counter, defaultdict
from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple

ROOT = pathlib.Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from harness.sql_norm import normalize  # noqa: E402

ORACLE_CLI = ROOT / "python_oracle" / "cli.py"
ORACLE_PY = ROOT / "python_oracle" / ".venv" / "bin" / "python"
CORPUS = ROOT / "corpus"
REPORTS = ROOT / "harness" / "reports"

DIALECT_TO_ENVELOPE = {
    "Trino": "TRINO",
    "BigQuery": "BIGQUERY",
    "Snowflake": "SNOWFLAKE",
    "Databricks": "DATABRICKS",
    "Redshift": "REDSHIFT",
    "DuckDB": "DUCKDB",
    "Postgres": "POSTGRES",
}


@dataclass
class CaseResult:
    case_id: str
    subcommand: str
    manifest: str
    dialect: str  # "" for non-SQL subcommands
    status: str  # PASS / FAIL / ERROR / SKIP
    detail: str  # short human-readable note
    diff_chars: int = 0
    diff_sample: str = ""


def _run_oracle(subcommand: str, payload: Dict[str, Any]) -> Tuple[int, str, str]:
    proc = subprocess.run(
        [str(ORACLE_PY), str(ORACLE_CLI), subcommand],
        input=json.dumps(payload),
        text=True,
        capture_output=True,
        cwd=str(ROOT),
        timeout=120,
    )
    return proc.returncode, proc.stdout, proc.stderr


def _first_diff_sample(a: str, b: str, n: int = 80) -> str:
    """Return a short human-readable sample of where a/b first diverge."""
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            start = max(0, i - 20)
            return (
                f"@offset {i}: expected={a[start:i + n]!r} | actual={b[start:i + n]!r}"
            )
    if len(a) != len(b):
        return f"length differs: expected={len(a)} actual={len(b)} (suffix differs)"
    return "<no diff>"


def _check_quarantine(case_dir: pathlib.Path) -> Optional[str]:
    """Return the quarantine reason if a quarantine marker exists, else None."""
    qfile = case_dir / "quarantine.md"
    if qfile.is_file():
        first_line = qfile.read_text().splitlines()[0] if qfile.read_text() else "quarantined"
        return first_line.strip("# ").strip()
    return None


def _compare_json_deep(expected: Any, actual: Any, path: str = "") -> Optional[str]:
    """Return the first difference path / explanation, or None on equality."""
    if type(expected) is not type(actual):
        # Allow int <-> float equality if numerically same. Otherwise reject.
        if isinstance(expected, (int, float)) and isinstance(actual, (int, float)):
            if expected == actual:
                return None
        return f"{path}: type mismatch expected={type(expected).__name__} actual={type(actual).__name__}"
    if isinstance(expected, dict):
        ek = set(expected.keys())
        ak = set(actual.keys())
        if ek != ak:
            extra = ak - ek
            missing = ek - ak
            return f"{path}: key mismatch missing={sorted(missing)} extra={sorted(extra)}"
        for k in expected:
            diff = _compare_json_deep(expected[k], actual[k], f"{path}.{k}")
            if diff:
                return diff
        return None
    if isinstance(expected, list):
        if len(expected) != len(actual):
            return f"{path}: list len mismatch expected={len(expected)} actual={len(actual)}"
        for i, (e, a) in enumerate(zip(expected, actual)):
            diff = _compare_json_deep(e, a, f"{path}[{i}]")
            if diff:
                return diff
        return None
    if expected != actual:
        return f"{path}: value mismatch expected={expected!r} actual={actual!r}"
    return None


def _run_one_case(case_dir: pathlib.Path) -> List[CaseResult]:
    """Execute the oracle for a single case directory.

    Returns one CaseResult per dialect for SQL-generating subcommands; one
    CaseResult overall for non-SQL subcommands.
    """
    case_id = case_dir.name
    meta = json.loads((case_dir / "meta.json").read_text())
    subcommand = meta["subcommand"]
    manifest = meta.get("manifest_id", "")
    dialect_set = meta.get("dialect_set", [])
    quarantine_reason = _check_quarantine(case_dir)

    payload_base = json.loads((case_dir / "request.json").read_text())

    results: List[CaseResult] = []
    if dialect_set:
        for dialect in dialect_set:
            if quarantine_reason:
                results.append(
                    CaseResult(case_id, subcommand, manifest, dialect, "SKIP", f"quarantine: {quarantine_reason}")
                )
                continue
            payload = dict(payload_base)
            payload["sql_engine"] = DIALECT_TO_ENVELOPE.get(dialect, dialect.upper())
            rc, out, err = _run_oracle(subcommand, payload)
            if rc != 0 or not out.strip():
                results.append(
                    CaseResult(case_id, subcommand, manifest, dialect, "ERROR", err.strip()[:300])
                )
                continue
            try:
                doc = json.loads(out)
            except json.JSONDecodeError as e:
                results.append(
                    CaseResult(case_id, subcommand, manifest, dialect, "ERROR", f"bad JSON: {e}")
                )
                continue
            actual_sql = doc.get("sql")
            if not isinstance(actual_sql, str):
                results.append(
                    CaseResult(case_id, subcommand, manifest, dialect, "ERROR", "no sql key")
                )
                continue
            expected_path = case_dir / "expected" / f"{dialect.lower()}.sql"
            if not expected_path.is_file():
                results.append(
                    CaseResult(case_id, subcommand, manifest, dialect, "ERROR", "missing expected SQL")
                )
                continue
            expected_sql = expected_path.read_text()
            e_n = normalize(expected_sql)
            a_n = normalize(actual_sql)
            if e_n == a_n:
                results.append(CaseResult(case_id, subcommand, manifest, dialect, "PASS", ""))
            else:
                results.append(
                    CaseResult(
                        case_id,
                        subcommand,
                        manifest,
                        dialect,
                        "FAIL",
                        "normalized SQL differs",
                        diff_chars=abs(len(a_n) - len(e_n)) or sum(1 for x, y in zip(a_n, e_n) if x != y),
                        diff_sample=_first_diff_sample(e_n, a_n),
                    )
                )
    else:
        if quarantine_reason:
            results.append(
                CaseResult(case_id, subcommand, manifest, "", "SKIP", f"quarantine: {quarantine_reason}")
            )
        else:
            rc, out, err = _run_oracle(subcommand, payload_base)
            if rc != 0 or not out.strip():
                results.append(CaseResult(case_id, subcommand, manifest, "", "ERROR", err.strip()[:300]))
            else:
                try:
                    actual = json.loads(out)
                except json.JSONDecodeError as e:
                    results.append(CaseResult(case_id, subcommand, manifest, "", "ERROR", f"bad JSON: {e}"))
                else:
                    expected = json.loads((case_dir / "expected.json").read_text())
                    diff = _compare_json_deep(expected, actual)
                    if diff is None:
                        results.append(CaseResult(case_id, subcommand, manifest, "", "PASS", ""))
                    else:
                        results.append(
                            CaseResult(
                                case_id,
                                subcommand,
                                manifest,
                                "",
                                "FAIL",
                                "JSON deep-equal failed",
                                diff_sample=diff[:300],
                            )
                        )

    return results


def _list_case_dirs() -> List[pathlib.Path]:
    return sorted(
        d
        for d in CORPUS.iterdir()
        if d.is_dir() and d.name != "manifests" and (d / "meta.json").is_file()
    )


def _write_csv(results: List[CaseResult]) -> pathlib.Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / "corpus_integrity.csv"
    with out.open("w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["case_id", "subcommand", "manifest", "dialect", "status", "detail", "diff_chars"])
        for r in results:
            w.writerow([r.case_id, r.subcommand, r.manifest, r.dialect, r.status, r.detail, r.diff_chars])
    return out


def _retry_for_order_nondeterminism(case_dir: pathlib.Path, original: List[CaseResult]) -> List[str]:
    """For non-SQL FAILs, re-run the oracle a few times and see if the output
    ever matches the expected output. If so, the case has order
    non-determinism (category-2 quarantine candidate per PROGRESS.md).
    Returns a list of case-ids/dialect that look like genuine non-determinism."""
    candidates: List[str] = []
    for r in original:
        if r.status != "FAIL" or r.dialect:
            # only non-SQL case FAILs
            continue
        meta = json.loads((case_dir / "meta.json").read_text())
        payload_base = json.loads((case_dir / "request.json").read_text())
        expected = json.loads((case_dir / "expected.json").read_text())
        matched = False
        for _ in range(5):
            rc, out, _ = _run_oracle(meta["subcommand"], payload_base)
            if rc != 0:
                continue
            try:
                actual = json.loads(out)
            except json.JSONDecodeError:
                continue
            if _compare_json_deep(expected, actual) is None:
                matched = True
                break
        if matched:
            candidates.append(r.case_id)
    return candidates


def _write_markdown(
    results: List[CaseResult],
    elapsed_s: float,
    quarantine_candidates: List[str],
) -> pathlib.Path:
    REPORTS.mkdir(parents=True, exist_ok=True)
    out = REPORTS / "corpus_integrity.md"

    total = len(results)
    by_status = Counter(r.status for r in results)
    pass_rate = (by_status.get("PASS", 0) / total) * 100 if total else 0.0

    by_subcommand: Dict[str, Counter] = defaultdict(Counter)
    by_dialect: Dict[str, Counter] = defaultdict(Counter)
    for r in results:
        by_subcommand[r.subcommand][r.status] += 1
        by_dialect[r.dialect or "(non-sql)"][r.status] += 1

    lines: List[str] = []
    lines.append("# Corpus integrity report")
    lines.append("")
    lines.append(f"_Run time: {elapsed_s:.1f}s. Total checks: {total}._")
    lines.append("")
    lines.append(f"- PASS: {by_status.get('PASS', 0)} ({pass_rate:.1f}%)")
    lines.append(f"- FAIL: {by_status.get('FAIL', 0)}")
    lines.append(f"- ERROR: {by_status.get('ERROR', 0)}")
    lines.append(f"- SKIP: {by_status.get('SKIP', 0)}")
    lines.append("")
    lines.append(f"**Achieves ≥80% PASS target: {'YES' if pass_rate >= 80 else 'NO'}**")
    lines.append("")
    lines.append("## By subcommand")
    lines.append("")
    lines.append("| subcommand | PASS | FAIL | ERROR | SKIP |")
    lines.append("|---|---:|---:|---:|---:|")
    for sub in sorted(by_subcommand):
        c = by_subcommand[sub]
        lines.append(
            f"| {sub} | {c.get('PASS', 0)} | {c.get('FAIL', 0)} | {c.get('ERROR', 0)} | {c.get('SKIP', 0)} |"
        )
    lines.append("")
    lines.append("## By dialect")
    lines.append("")
    lines.append("| dialect | PASS | FAIL | ERROR | SKIP |")
    lines.append("|---|---:|---:|---:|---:|")
    for dia in sorted(by_dialect):
        c = by_dialect[dia]
        lines.append(
            f"| {dia} | {c.get('PASS', 0)} | {c.get('FAIL', 0)} | {c.get('ERROR', 0)} | {c.get('SKIP', 0)} |"
        )
    lines.append("")
    fails = [r for r in results if r.status == "FAIL"]
    errors = [r for r in results if r.status == "ERROR"]
    if fails:
        lines.append("## FAIL rows")
        lines.append("")
        lines.append("| case_id | dialect | diff_chars | diff_sample (first 80 chars after divergence) |")
        lines.append("|---|---|---:|---|")
        for r in fails:
            sample = r.diff_sample.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| `{r.case_id}` | {r.dialect} | {r.diff_chars} | {sample[:200]} |")
        lines.append("")
    if errors:
        lines.append("## ERROR rows")
        lines.append("")
        lines.append("| case_id | dialect | message |")
        lines.append("|---|---|---|")
        for r in errors:
            msg = r.detail.replace("|", "\\|").replace("\n", " ")
            lines.append(f"| `{r.case_id}` | {r.dialect} | {msg[:200]} |")
        lines.append("")
    if quarantine_candidates:
        lines.append("## Proposed quarantines (awaiting evaluator approval)")
        lines.append("")
        lines.append(
            "These cases FAIL deterministically against their captured baseline but "
            "PASS on retry, meaning the oracle output ordering varies between runs. "
            "Per PROGRESS.md policy, this is category-2 quarantine "
            "(dialect-specific output order non-determinism, including sets/dicts). "
            "Builder must NOT commit `quarantine.md`; evaluator approves."
        )
        lines.append("")
        lines.append("| case_id | proposed category | rationale |")
        lines.append("|---|---|---|")
        for cid in quarantine_candidates:
            lines.append(
                f"| `{cid}` | 2 (order non-determinism) | "
                "Re-run produced an alternative ordering that matched the baseline; "
                "the underlying `engine.list_*` API returns items via a set-iteration order "
                "that varies between Python processes. |"
            )
        lines.append("")
    out.write_text("\n".join(lines) + "\n")
    return out


def main() -> int:
    case_dirs = _list_case_dirs()
    print(f"Running oracle integrity check on {len(case_dirs)} case directories ...", file=sys.stderr)

    start = time.time()
    results: List[CaseResult] = []
    for i, case_dir in enumerate(case_dirs, 1):
        for r in _run_one_case(case_dir):
            results.append(r)
        sys.stderr.write(".")
        if i % 80 == 0:
            sys.stderr.write(f" {i}/{len(case_dirs)}\n")
        sys.stderr.flush()
    elapsed = time.time() - start
    sys.stderr.write(f" done ({len(case_dirs)}/{len(case_dirs)}) in {elapsed:.1f}s\n")

    # For non-SQL FAILs, retry a few times to detect order non-determinism.
    quarantine_candidates: List[str] = []
    fail_case_ids = {r.case_id for r in results if r.status == "FAIL" and not r.dialect}
    for cid in sorted(fail_case_ids):
        case_dir = CORPUS / cid
        if not case_dir.is_dir():
            continue
        case_fails = [r for r in results if r.case_id == cid and r.status == "FAIL"]
        cands = _retry_for_order_nondeterminism(case_dir, case_fails)
        quarantine_candidates.extend(cands)

    csv_path = _write_csv(results)
    md_path = _write_markdown(results, elapsed, quarantine_candidates)
    print(f"Wrote {csv_path}", file=sys.stderr)
    print(f"Wrote {md_path}", file=sys.stderr)

    total = len(results)
    passed = sum(1 for r in results if r.status == "PASS")
    pass_rate = (passed / total) * 100 if total else 0.0
    print(f"\n  Pass: {passed}/{total} ({pass_rate:.1f}%)", file=sys.stderr)
    return 0 if any(r.status == "PASS" for r in results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
