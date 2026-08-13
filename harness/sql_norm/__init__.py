"""SQL normalizer used by ``harness/run_oracle.py``.

The normalizer is **conservative** — every rule must be semantic-preserving
and documented in ``rules/<name>.md``. New rules require ≥3 distinct corpus
cases that need them (exempted during Phase 1b corpus seed; enforced thereafter).
See ``README.md`` for the policy.
"""

from harness.sql_norm.normalizer import normalize, normalize_sql, NormalizerRule

__all__ = ["normalize", "normalize_sql", "NormalizerRule"]
