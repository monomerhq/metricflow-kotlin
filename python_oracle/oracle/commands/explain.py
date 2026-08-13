"""``explain`` — query -> rendered SQL via ``MetricFlowEngine.explain``."""

from __future__ import annotations

import datetime
from typing import Any, Mapping, Optional

from metricflow.engine.metricflow_engine import (
    MetricFlowQueryRequest,
    MetricFlowQueryType,
)

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import bind_parameter_set_to_dict


def _parse_iso(value: Optional[str]) -> Optional[datetime.datetime]:
    if value is None:
        return None
    return datetime.datetime.fromisoformat(value)


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """Execute the explain entry point.

    Input ``args``:
        metric_names, group_by_names, where_constraints, order_by_names,
        limit, time_constraint_start, time_constraint_end, saved_query_name,
        min_max_only, apply_group_by, order_output_columns_by_input_order.
    """
    manifest = build_manifest_from_input(input_data)
    sql_engine = parse_sql_engine(input_data.get("sql_engine"))
    engine = create_engine(manifest=manifest, sql_engine=sql_engine)

    args: Mapping[str, Any] = input_data.get("args") or {}
    request = MetricFlowQueryRequest.create(
        saved_query_name=args.get("saved_query_name"),
        metric_names=args.get("metric_names"),
        group_by_names=args.get("group_by_names"),
        limit=args.get("limit"),
        time_constraint_start=_parse_iso(args.get("time_constraint_start")),
        time_constraint_end=_parse_iso(args.get("time_constraint_end")),
        where_constraints=args.get("where_constraints"),
        order_by_names=args.get("order_by_names"),
        min_max_only=bool(args.get("min_max_only", False)),
        apply_group_by=bool(args.get("apply_group_by", True)),
        order_output_columns_by_input_order=bool(
            args.get("order_output_columns_by_input_order", False)
        ),
        query_type=MetricFlowQueryType.METRIC,
    )

    result = engine.explain(request)
    sql_statement = result.sql_statement

    return {
        "sql": sql_statement.sql,
        "bind_parameters": bind_parameter_set_to_dict(sql_statement.bind_parameter_set),
        "metadata": {
            "sql_engine": sql_engine.value,
            "dataflow_plan_id": result.dataflow_plan.dag_id.id_str,
            "query_spec_summary": {
                "metric_specs": [s.element_name for s in result.query_spec.metric_specs],
                "dimension_specs": [s.dunder_name for s in result.query_spec.dimension_specs],
                "time_dimension_specs": [
                    s.dunder_name for s in result.query_spec.time_dimension_specs
                ],
                "entity_specs": [s.dunder_name for s in result.query_spec.entity_specs],
                "min_max_only": result.query_spec.min_max_only,
            },
        },
    }
