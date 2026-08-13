"""``entities_for_metrics`` — entities common to a metric set."""

from __future__ import annotations

from typing import Any, Mapping

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import entity_to_dict


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """Args: ``metric_names`` (required list)."""
    manifest = build_manifest_from_input(input_data)
    engine = create_engine(
        manifest=manifest, sql_engine=parse_sql_engine(input_data.get("sql_engine"))
    )
    args: Mapping[str, Any] = input_data.get("args") or {}
    metric_names = list(args.get("metric_names") or [])

    entities = engine.entities_for_metrics(metric_names=metric_names)
    return {"entities": [entity_to_dict(e) for e in entities]}
