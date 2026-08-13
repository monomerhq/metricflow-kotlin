"""Apply the canonical Pydantic transformer to a corpus manifest JSON envelope.

Reads one JSON envelope ({"semantic_models": [...], "metrics": [...],
"project_configuration": {...}, "saved_queries": [...]}) from stdin, runs
``PydanticSemanticManifestTransformer.transform(...)`` (the same path the
oracle CLI walks before validation / engine init), and writes the transformed
manifest to stdout.

This helper exists so the Kotlin transformation module's parity test can
compare its own output against the Python oracle's output, manifest by
manifest. The oracle CLI does not surface the transformed-but-not-validated
manifest, so we go direct instead of layering yet another CLI command.

This file lives under ``harness/`` (not ``python_oracle/oracle/``) so the
oracle CLI is unaffected — only the Phase 3 W2a parity test invokes it.

Usage::

    python harness/python_transform.py < corpus/manifests/foo.json
"""

from __future__ import annotations

import json
import sys

from oracle.manifest import build_manifest_from_input


def _dump(obj):
    """Serialise the Pydantic v1 manifest to a plain dict via JSON round-trip.

    Mirrors the trick used in ``harness.manifest_loader._pyd_dump`` so enums
    become their ``.value`` strings rather than ``EnumClass.MEMBER`` literals.
    """
    if hasattr(obj, "model_dump"):
        return obj.model_dump(mode="json", by_alias=True, exclude_none=False)
    return json.loads(obj.json(by_alias=True, exclude_none=False))


def main() -> int:
    text = sys.stdin.read()
    if not text.strip():
        print("error: empty stdin", file=sys.stderr)
        return 2
    data = json.loads(text)
    manifest = build_manifest_from_input(data, apply_transform=True)
    json.dump(_dump(manifest), sys.stdout, default=str)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
