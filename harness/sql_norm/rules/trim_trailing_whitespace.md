# trim_trailing_whitespace

## What

Strip trailing spaces and tabs from each line (everything matching `\s+$`
**per line**, not across lines). Implementation: `line.rstrip()` for each line.

## Why this is semantic-preserving

SQL tokens are separated by whitespace; trailing whitespace before a newline
does not change tokenization. There is no SQL construct where a trailing
space at end-of-line carries meaning. Even in string literals, multi-line
string literals require explicit concatenation or quoting that survives the
rstrip (the trailing space would be *inside* the quotes, so the line's
content already terminates with a closing quote or backslash — both are
non-whitespace characters and survive rstrip).

The only theoretical case where rstrip could lose meaning is in a literal
that ends in a `\r` (carriage return) — already handled by the
`normalize_line_endings` rule which runs first, converting any meaningful
`\r` into `\n` (a line terminator, not content). After that, all trailing
whitespace is by definition outside any literal.

## When this fires in practice

* Manual edits to corpus SQL files where the editor inserts trailing spaces.
* Oracle outputs that re-indent or pad lines for readability.

## Risk of false positive

Negligible. The only way to construct a counterexample is a SQL string
literal that intentionally contains trailing whitespace at end of line before
the literal continues — uncommon and easily avoidable in corpus inputs.

## Phase 1b note

Seeded without the 3-case threshold. Standard text-file hygiene, predates
metricflow.
