#!/usr/bin/env python3
"""Convenience entry point at ``python_oracle/cli.py``.

Equivalent to ``python -m oracle <sub>`` once the venv is on PYTHONPATH. The
file lives at the project root so callers can do::

    python python_oracle/cli.py <sub> < input.json

without first activating the venv. The runnable interpreter is
``python_oracle/.venv/bin/python``.
"""

from __future__ import annotations

import os
import sys

# Make the ``oracle`` package importable regardless of caller cwd.
_THIS_DIR = os.path.dirname(os.path.abspath(__file__))
if _THIS_DIR not in sys.path:
    sys.path.insert(0, _THIS_DIR)

from oracle.cli import main  # noqa: E402  (path tweak before import)


if __name__ == "__main__":
    sys.exit(main())
