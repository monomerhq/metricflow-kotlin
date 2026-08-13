# normalize_line_endings

## What

Replace `\r\n` and bare `\r` line endings with `\n`.

## Why this is semantic-preserving

In every SQL dialect, line breaks function only as token separators or comment
terminators. The exact byte sequence of the line break (`\n`, `\r\n`, `\r`)
has no effect on parsing — `--`-style line comments terminate on any of these
in the SQL standard's recommended lexer, and most production SQL parsers
accept all three interchangeably.

## When this fires in practice

* Strings read from Git on Windows checkouts (autocrlf).
* SQL captured through subprocess pipes that go through tty layers.
* Cross-platform editing of corpus files.

## Risk of false positive

None known. The transform converts every line ending to LF; there is no
context in which CRLF would carry information distinct from LF in SQL.

## Phase 1b note

Seeded without the 3-case threshold. This is a standard reading-of-files
hygiene transform that the Python world settled long ago, and there is no
plausible corpus case where preserving CRLF would matter.
