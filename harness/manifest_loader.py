"""Load a YAML manifest directory and dump it to the oracle CLI envelope shape.

We reuse the upstream metricflow parser so the resulting dicts are guaranteed
to round-trip through ``PydanticSemanticModel.parse_obj`` etc. (which is what
``oracle/manifest.py::build_manifest`` does internally).

Stable, deterministic source-schema replacement: ``$source_schema`` -> a
constant 27-char ASCII identifier so every corpus run produces the same SQL.
The number 27 matches the default length used by upstream's session-random
``mf_test_<date>_<random>`` schema, which is what the upstream snapshots
replace with 27 ``*``s. Keeping the same length means our diff harness can do
the same star-replacement and compare against either oracle output or upstream
snapshots without changing line lengths.
"""

from __future__ import annotations

import json
import pathlib
import sys
from typing import Any, Dict, Iterable


# 27 chars total. Picked so it has no regex-meta chars and matches the upstream
# default schema length (``mf_test_2024_03_15_abcdef``-ish). Keep alphanum +
# underscore only so SQL identifiers stay valid wherever this gets stamped.
CORPUS_SOURCE_SCHEMA = "mf_corpus_2026_05_11_static"
assert len(CORPUS_SOURCE_SCHEMA) == 27
REPOSITORY_ROOT = pathlib.Path(__file__).resolve().parents[1]


def load_manifest_dict(yaml_dir: pathlib.Path) -> Dict[str, Any]:
    """Read a manifest YAML directory and return the oracle-CLI envelope dict.

    The returned dict has keys ``semantic_models``, ``metrics``,
    ``project_configuration``, ``saved_queries`` -- the shape the oracle CLI
    ingests. ``$source_schema`` is substituted with ``CORPUS_SOURCE_SCHEMA``
    via the upstream string.Template substitution path so we hit the same
    normalization the upstream tests use.
    """
    # Import lazily so this module can be imported in isolation for tests.
    from metricflow_semantic_interfaces.parsing.dir_to_model import (
        parse_directory_of_yaml_files_to_semantic_manifest,
    )

    build_result = parse_directory_of_yaml_files_to_semantic_manifest(
        str(yaml_dir),
        template_mapping={"source_schema": CORPUS_SOURCE_SCHEMA},
    )
    manifest = build_result.semantic_manifest

    return {
        "semantic_models": [_pyd_dump(sm) for sm in manifest.semantic_models],
        "metrics": [_pyd_dump(m) for m in manifest.metrics],
        "project_configuration": _pyd_dump(manifest.project_configuration),
        "saved_queries": [_pyd_dump(sq) for sq in (manifest.saved_queries or [])],
    }


def _pyd_dump(obj: Any) -> Any:
    """Serialize a Pydantic v1/v2 model into a plain dict that ``parse_obj``
    will accept again.

    metricflow_semantic_interfaces uses pydantic v1, whose ``.dict()`` leaves
    enum members as ``EnumClass.MEMBER`` python objects -- they json-encode as
    ``"EnumClass.MEMBER"`` strings which then fail ``parse_obj`` because the
    enums only accept their ``.value`` strings (e.g. ``"primary"``). Round-
    tripping through ``.json()`` plus ``json.loads`` resolves both issues at
    once: enums become their value strings, datetimes become ISO strings.
    """
    if hasattr(obj, "model_dump"):  # pydantic v2 (just in case the upstream upgrades)
        dumped = obj.model_dump(mode="json", by_alias=True, exclude_none=False)
    elif hasattr(obj, "json") and hasattr(obj, "dict"):  # pydantic v1
        dumped = json.loads(obj.json(by_alias=True, exclude_none=False))
    else:
        dumped = obj
    return _normalize_repository_paths(dumped)


def _normalize_repository_paths(value: Any, repository_root: pathlib.Path = REPOSITORY_ROOT) -> Any:
    """Replace checkout-specific absolute paths with repository-relative paths.

    Corpus data can be regenerated from a different worktree than the one doing
    the verification. Prefer the active repository root, then recognize the
    immutable upstream fixture subtree so a foreign checkout prefix can never be
    persisted in a request fixture.
    """
    if isinstance(value, dict):
        return {key: _normalize_repository_paths(item, repository_root) for key, item in value.items()}
    if isinstance(value, list):
        return [_normalize_repository_paths(item, repository_root) for item in value]
    if not isinstance(value, str):
        return value

    candidate = pathlib.Path(value)
    if not candidate.is_absolute():
        return value
    try:
        return candidate.relative_to(repository_root).as_posix()
    except ValueError:
        parts = candidate.parts
        upstream_anchor = ("python_oracle", "upstream")
        for index in range(len(parts) - len(upstream_anchor) + 1):
            if tuple(parts[index : index + len(upstream_anchor)]) == upstream_anchor:
                return pathlib.PurePosixPath(*parts[index:]).as_posix()
        return value


def write_manifest_json(yaml_dir: pathlib.Path, out_path: pathlib.Path) -> Dict[str, Any]:
    """Convert one YAML manifest directory and write the JSON envelope to disk."""
    envelope = load_manifest_dict(yaml_dir)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(envelope, indent=2, sort_keys=False, default=str))
    return envelope


def iter_manifest_dirs(root: pathlib.Path) -> Iterable[pathlib.Path]:
    """Yield each semantic-manifest YAML directory under ``root``.

    A directory is considered a manifest if it contains ``project_configuration.yaml``
    plus at least one other YAML file (``.yaml`` or ``.yml``) holding model or
    metric definitions. ``shared/`` only has a project_configuration so is
    excluded.
    """
    for child in sorted(root.iterdir()):
        if not child.is_dir():
            continue
        has_project = (child / "project_configuration.yaml").is_file() or (child / "manifest.yaml").is_file()
        if not has_project:
            continue
        # Count yaml files besides project_configuration.yaml; anything else is
        # a semantic model / metric / saved query definition.
        other_yamls = [
            p
            for p in child.rglob("*")
            if p.is_file()
            and p.suffix in {".yaml", ".yml"}
            and p.name != "project_configuration.yaml"
        ]
        if not other_yamls:
            continue
        yield child


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: manifest_loader.py <yaml_dir> <out_json>", file=sys.stderr)
        return 2
    yaml_dir = pathlib.Path(argv[1])
    out_path = pathlib.Path(argv[2])
    write_manifest_json(yaml_dir, out_path)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
