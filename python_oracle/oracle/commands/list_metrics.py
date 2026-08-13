"""``list_metrics`` — metric catalog with optional dimension expansion."""

from __future__ import annotations

from typing import Any, Mapping

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import metric_to_dict


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """Args: ``include_dimensions`` (bool, default True)."""
    manifest = build_manifest_from_input(input_data)
    engine = create_engine(
        manifest=manifest, sql_engine=parse_sql_engine(input_data.get("sql_engine"))
    )
    args: Mapping[str, Any] = input_data.get("args") or {}
    include_dimensions = bool(args.get("include_dimensions", True))

    metrics = engine.list_metrics(include_dimensions=include_dimensions)
    return {"metrics": [metric_to_dict(m) for m in metrics]}
