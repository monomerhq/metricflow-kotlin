"""Exercise the integrity command against a real oracle and a corrupted SQL baseline."""

import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch

from harness import run_oracle


class OracleIntegrityGateTest(unittest.TestCase):
    def test_empty_corpus_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(run_oracle, "CORPUS", root), patch.object(run_oracle, "REPORTS", root / "reports"):
                self.assertEqual(1, run_oracle.main())

    def test_one_pass_cannot_hide_a_corrupted_sql_baseline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = run_oracle.ROOT / "corpus/explain__minimal_fixture__bookings_by_metric_time"
            good = root / "valid"
            shutil.copytree(source, good)
            meta = json.loads((good / "meta.json").read_text())
            meta["dialect_set"] = ["DuckDB"]
            (good / "meta.json").write_text(json.dumps(meta))
            with patch.object(run_oracle, "CORPUS", root), patch.object(run_oracle, "REPORTS", root / "reports"):
                self.assertEqual(0, run_oracle.main())
                bad = root / "corrupted"
                shutil.copytree(good, bad)
                (bad / "expected/duckdb.sql").write_text("SELECT 0 AS deliberately_wrong_total")
                self.assertEqual(1, run_oracle.main())
                report = (root / "reports/corpus_integrity.md").read_text()
                self.assertIn("- PASS: 1", report)
                self.assertIn("- FAIL: 1", report)
                self.assertIn("Achieves 100% PASS target: NO", report)


if __name__ == "__main__":
    unittest.main()
