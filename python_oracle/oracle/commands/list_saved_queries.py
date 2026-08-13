"""``list_saved_queries`` — saved-query catalog (manifest's ``saved_queries`` block)."""

from __future__ import annotations

from typing import Any, Mapping

from oracle.engine_factory import create_engine, parse_sql_engine
from oracle.manifest import build_manifest_from_input
from oracle.serialize import saved_query_to_dict


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """No args other than the manifest."""
    manifest = build_manifest_from_input(input_data)
    engine = create_engine(
        manifest=manifest, sql_engine=parse_sql_engine(input_data.get("sql_engine"))
    )
    saved_queries = engine.list_saved_queries()
    return {"saved_queries": [saved_query_to_dict(sq) for sq in saved_queries]}
