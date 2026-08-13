"""``list_group_bys`` — dimensions + entities for a metric set."""

from __future__ import annotations

from typing import Any, Mapping

from metricflow.engine.metricflow_engine import GroupByOrderByAttribute
from metricflow.engine.models import Dimension, Entity

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import dimension_to_dict, entity_to_dict


def _parse_order_by(value: Any) -> GroupByOrderByAttribute:
    if value is None:
        return GroupByOrderByAttribute.DUNDER_NAME
    upper = str(value).upper()
    try:
        return GroupByOrderByAttribute[upper]
    except KeyError as exc:
        valid = ", ".join(a.name for a in GroupByOrderByAttribute)
        raise ValueError(f"Unknown order_by '{value}'. Valid: {valid}") from exc


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """Args: ``metric_names`` (optional), ``include_derived_time_granularities`` (bool, default False), ``order_by``."""
    manifest = build_manifest_from_input(input_data)
    engine = create_engine(
        manifest=manifest, sql_engine=parse_sql_engine(input_data.get("sql_engine"))
    )
    args: Mapping[str, Any] = input_data.get("args") or {}

    items = engine.list_group_bys(
        metric_names=args.get("metric_names"),
        include_derived_time_granularities=bool(
            args.get("include_derived_time_granularities", False)
        ),
        order_by=_parse_order_by(args.get("order_by")),
    )

    dimensions = [dimension_to_dict(i) for i in items if isinstance(i, Dimension)]
    entities = [entity_to_dict(i) for i in items if isinstance(i, Entity)]
    return {"dimensions": dimensions, "entities": entities}
