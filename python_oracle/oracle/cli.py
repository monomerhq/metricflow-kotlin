"""Top-level CLI dispatcher.

Usage::

    python -m oracle <subcommand> < input.json
    python cli.py <subcommand>            < input.json
    metricflow-oracle <subcommand>        < input.json

The subcommand reads one JSON document from stdin, dispatches to the matching
``oracle.commands.*`` module, and writes one JSON document to stdout. Errors
are reported on stderr with a non-zero exit code; logs (anything written via
the ``logging`` module) also go to stderr so stdout stays a clean JSON stream.
"""

from __future__ import annotations

import json
import logging
import sys
import traceback
from typing import Sequence

from oracle.commands import COMMANDS


def _configure_logging() -> None:
    """Send every log line to stderr so stdout stays valid JSON."""
    handler = logging.StreamHandler(stream=sys.stderr)
    handler.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
    root = logging.getLogger()
    # Don't double-attach if the user pre-configured logging (e.g. tests).
    if not any(isinstance(h, logging.StreamHandler) and h.stream is sys.stderr for h in root.handlers):
        root.addHandler(handler)
    root.setLevel(logging.WARNING)


def _usage() -> str:
    return f"usage: oracle <{'|'.join(sorted(COMMANDS))}>"


def main(argv: Sequence[str] | None = None) -> int:
    """Entry point. Returns the process exit code instead of calling ``sys.exit``."""
    _configure_logging()
    args = list(argv if argv is not None else sys.argv[1:])

    if not args or args[0] in {"-h", "--help"}:
        print(_usage(), file=sys.stderr)
        return 0 if args else 2

    subcommand = args[0]
    if subcommand not in COMMANDS:
        print(f"error: unknown subcommand '{subcommand}'", file=sys.stderr)
        print(_usage(), file=sys.stderr)
        return 2

    try:
        input_text = sys.stdin.read()
        if not input_text.strip():
            raise ValueError("Empty stdin: expected one JSON document.")
        input_data = json.loads(input_text)
    except json.JSONDecodeError as exc:
        print(f"error: invalid JSON on stdin: {exc.msg}", file=sys.stderr)
        return 1
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    try:
        output = COMMANDS[subcommand](input_data)
    except Exception as exc:  # noqa: BLE001
        print(f"error: {type(exc).__name__}: {exc}", file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        return 1

    json.dump(output, sys.stdout, default=str)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
