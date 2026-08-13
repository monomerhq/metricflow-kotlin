"""Allow ``python -m oracle <sub>`` invocation."""

from __future__ import annotations

import sys

from oracle.cli import main

if __name__ == "__main__":
    sys.exit(main())
