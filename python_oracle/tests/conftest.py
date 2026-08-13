"""Pytest configuration for the oracle smoke tests.

Adds ``python_oracle/`` to ``sys.path`` so the tests can ``import oracle.*``
when invoked directly (``.venv/bin/python -m pytest tests/``).
"""

from __future__ import annotations

import os
import sys

_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)
