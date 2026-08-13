"""``explain_get_dimension_values`` — render the dimension-value SQL.

Mirrors ``MetricFlowEngine.explain_get_dimension_values``: never executes,
just builds the dataflow plan and renders SQL for fetching distinct values of
a single dimension.
"""

from __future__ import annotations

import datetime
from typing import Any, Mapping, Optional

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import bind_parameter_set_to_dict


def _parse_iso(value: Optional[str]) -> Optional[datetime.datetime]:
    if value is None:
        return None
    return datetime.datetime.fromisoformat(value)


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """Args: ``metric_names``, ``get_group_by_values``, ``time_constraint_start``, ``time_constraint_end``, ``min_max_only``."""
    manifest = build_manifest_from_input(input_data)
    sql_engine = parse_sql_engine(input_data.get("sql_engine"))
    engine = create_engine(manifest=manifest, sql_engine=sql_engine)

    args: Mapping[str, Any] = input_data.get("args") or {}
    result = engine.explain_get_dimension_values(
        metric_names=args.get("metric_names"),
        get_group_by_values=args.get("get_group_by_values"),
        time_constraint_start=_parse_iso(args.get("time_constraint_start")),
        time_constraint_end=_parse_iso(args.get("time_constraint_end")),
        min_max_only=bool(args.get("min_max_only", False)),
    )
    sql_statement = result.sql_statement
    return {
        "sql": sql_statement.sql,
        "bind_parameters": bind_parameter_set_to_dict(sql_statement.bind_parameter_set),
        "metadata": {"sql_engine": sql_engine.value},
    }
