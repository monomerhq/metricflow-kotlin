# collapse_blank_lines

## What

Collapse runs of three or more consecutive `\n` characters down to exactly two
(i.e., allow at most one blank line between non-blank lines). After the
normalizer's full pass, a final `rstrip("\n")` removes trailing newlines so the
normalized form ends in exactly the last non-blank line.

## Why this is semantic-preserving

Blank lines are not tokens in any SQL dialect. SQL is whitespace-insensitive
between tokens (with whitespace including newlines). Two blank lines, ten
blank lines, or zero blank lines between two statements produce the same
parse.

In edge cases where SQL clients use `;` as a statement terminator and require
*something* between statements, that something is the semicolon — not the
blank lines. Our oracle outputs single statements without trailing
semicolons, so even this edge case does not apply.

## When this fires in practice

* Oracle output passing through a pretty-printer that double-spaces sections.
* Upstream snapshots that include section banners with empty lines for
  readability vs our oracle's tighter output.

## Risk of false positive

Negligible. Whitespace runs between tokens are fungible in standard SQL.

## Phase 1b note

Seeded without the 3-case threshold. Whitespace hygiene.
