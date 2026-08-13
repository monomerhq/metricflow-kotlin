# harness/sql_norm

Conservative SQL string normalizer used by the differential-test harness
(`harness/run_oracle.py`). Every rule must be **semantic-preserving** — i.e.
two strings that differ only in ways covered by an active rule must be
equivalent SQL according to any reasonable parser.

## Active rules (seed)

| Rule | Description | Doc |
|---|---|---|
| `normalize_line_endings` | CRLF / CR → LF | [rules/normalize_line_endings.md](rules/normalize_line_endings.md) |
| `trim_trailing_whitespace` | Strip trailing spaces/tabs per line | [rules/trim_trailing_whitespace.md](rules/trim_trailing_whitespace.md) |
| `collapse_blank_lines` | Collapse 3+ newlines to 2 (max 1 blank line) | [rules/collapse_blank_lines.md](rules/collapse_blank_lines.md) |

After applying every rule the normalizer also `rstrip("\n")`s the result so
trailing blank-line variations don't matter.

## Policy (per PROGRESS.md, 2026-05-11)

> A. Acceptance bar — strict 100% after conservative semantic-preserving normalizer.
> New normalizer rules must be necessary for ≥3 cases. Quarantine reasons
> limited to 3 categories. Evaluator-only approval for quarantine.

**Phase 1b exemption (this directory):** the seed rules above were added without
the 3-case threshold because the corpus is being built simultaneously. Any rule
added **after** Phase 1b must satisfy the 3-case empirical threshold (cite at
least three corpus cases that need the rule, where "need" means a true
semantic-preserving normalization). The 3-case rule is enforced by the
evaluator skill when reviewing Phase 2+ PRs.

## How to add a new rule

1. Verify the diff cases that motivate the rule. Cite at least 3 distinct
   `corpus/<case>` directories with `expected/<dialect>.sql` vs current oracle
   output that the rule would equate. The 3 cases must be different in
   substance — adding three trivially-different copies of the same test does
   not count.
2. Implement a small pure function with a unit-testable contract.
3. Add it to `_DEFAULT_RULES` in `normalizer.py` with a one-line description.
4. Create `rules/<rule_name>.md` documenting:
   - Why it's semantic-preserving (explicit argument, not just "looks safe")
   - The 3+ corpus cases that need it (case IDs and a 2-line diff sample)
   - Any known false-positive risks
5. Re-run `harness/run_oracle.py` to confirm the pass-rate goes up and nothing
   regresses.

## Anti-pattern

Don't add a rule that masks a real difference. If two SQL strings differ in
something like `COALESCE` vs `IFNULL`, that **is** dialect-specific syntax that
the renderer should produce identically. Fix the renderer, don't paper over it
with a normalizer rule.

Also do not add rules that depend on parsing the SQL. The normalizer is pure
string-level. Anything more invasive belongs in a separate `sql_norm_ast/`
module (not yet introduced).
