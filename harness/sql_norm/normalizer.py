"""Conservative SQL normalizer.

Each rule must be semantic-preserving and documented under
``harness/sql_norm/rules/<name>.md``. The seed rules are intentionally minimal
-- normalizing whitespace/line-endings only. More rules require ≥3 cases of
empirical need (see ``README.md`` for policy and Phase 1b exemption).

Usage:

    from harness.sql_norm import normalize
    a_n = normalize(actual_sql)
    e_n = normalize(expected_sql)
    if a_n == e_n: ...  # PASS
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Callable, Tuple


@dataclass(frozen=True)
class NormalizerRule:
    """One semantic-preserving normalization rule.

    Attributes:
        name: short identifier used in tooling/reports. Matches the markdown
            doc filename in ``rules/``.
        description: one-line summary.
        apply: ``str -> str`` transform.
    """

    name: str
    description: str
    apply: Callable[[str], str]


def _normalize_line_endings(sql: str) -> str:
    """Rule: normalize_line_endings (\r\n / \r -> \n).

    Required for diffing SQL strings that originated from Windows-edited files
    or from oracle output captured through different transports. Semantic
    preservation: line breaks have no SQL meaning -- whitespace between tokens
    is fungible.
    """
    return sql.replace("\r\n", "\n").replace("\r", "\n")


def _trim_trailing_whitespace(sql: str) -> str:
    """Rule: trim_trailing_whitespace.

    Strips trailing spaces/tabs per line. Semantic preservation: trailing
    whitespace at end-of-line is purely cosmetic in any SQL dialect; tokens
    are not split by it.
    """
    return "\n".join(line.rstrip() for line in sql.split("\n"))


_BLANK_RUN_RE = re.compile(r"\n{3,}")


def _collapse_blank_lines(sql: str) -> str:
    """Rule: collapse_blank_lines (3+ consecutive newlines -> 2).

    Three or more newlines in a row produce two or more blank lines, which is
    purely cosmetic and varies between rendering paths. Semantic preservation:
    SQL is whitespace-insensitive between tokens.
    """
    return _BLANK_RUN_RE.sub("\n\n", sql)


# Default rule list. Order matters: line-ending normalization must run first
# so the others see only "\n".
_DEFAULT_RULES: Tuple[NormalizerRule, ...] = (
    NormalizerRule(
        name="normalize_line_endings",
        description=r"Convert CRLF/CR line endings to LF.",
        apply=_normalize_line_endings,
    ),
    NormalizerRule(
        name="trim_trailing_whitespace",
        description="Strip trailing spaces/tabs per line.",
        apply=_trim_trailing_whitespace,
    ),
    NormalizerRule(
        name="collapse_blank_lines",
        description="Collapse runs of 3+ newlines to 2 (i.e., max 1 blank line).",
        apply=_collapse_blank_lines,
    ),
)


def normalize_sql(sql: str, *, rules: Tuple[NormalizerRule, ...] = _DEFAULT_RULES) -> str:
    """Apply every rule in order. ``rules`` defaults to the seeded set."""
    out = sql
    for rule in rules:
        out = rule.apply(out)
    # Final trim of a single trailing newline so trailing-blank-line variation
    # between sources doesn't matter. This is implicit in trim_trailing + the
    # collapse rule but explicit final ``rstrip("\n")`` makes the contract
    # exact: normalized form has no trailing newlines.
    return out.rstrip("\n")


# Public alias to keep ``from harness.sql_norm import normalize`` short.
normalize = normalize_sql


def active_rules() -> Tuple[NormalizerRule, ...]:
    """Return the active default rule set (for introspection / reporting)."""
    return _DEFAULT_RULES
