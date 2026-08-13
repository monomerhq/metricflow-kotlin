"""Construct a ``MetricFlowEngine`` for the oracle CLI.

The engine needs a ``SqlClient`` to know which dialect to render and how to
spell bind-parameter keys. The oracle never executes SQL, so the
``SqlClient`` we hand the engine raises ``NotImplementedError`` for
``query``/``execute``/``dry_run``. Only ``sql_engine_type``,
``sql_plan_renderer``, and ``render_bind_parameter_key`` are ever consulted on
the ``MetricFlowEngine.explain()`` path.
"""

from __future__ import annotations

from typing import Optional

from metricflow_semantics.model.semantic_manifest_lookup import SemanticManifestLookup
from metricflow_semantics.sql.sql_bind_parameters import SqlBindParameterSet

from metricflow.data_table.mf_table import MetricFlowDataTable
from metricflow.engine.metricflow_engine import MetricFlowEngine
from metricflow.protocols.sql_client import SqlClient, SqlEngine
from metricflow.sql.render.big_query import BigQuerySqlPlanRenderer
from metricflow.sql.render.databricks import DatabricksSqlPlanRenderer
from metricflow.sql.render.duckdb_renderer import DuckDbSqlPlanRenderer
from metricflow.sql.render.postgres import PostgresSQLSqlPlanRenderer
from metricflow.sql.render.redshift import RedshiftSqlPlanRenderer
from metricflow.sql.render.snowflake import SnowflakeSqlPlanRenderer
from metricflow.sql.render.sql_plan_renderer import DefaultSqlPlanRenderer, SqlPlanRenderer
from metricflow.sql.render.trino import TrinoSqlPlanRenderer
from metricflow_semantic_interfaces.implementations.semantic_manifest import (
    PydanticSemanticManifest,
)


_RENDERER_FACTORIES: dict[SqlEngine, type[SqlPlanRenderer]] = {
    SqlEngine.TRINO: TrinoSqlPlanRenderer,
    SqlEngine.POSTGRES: PostgresSQLSqlPlanRenderer,
    SqlEngine.BIGQUERY: BigQuerySqlPlanRenderer,
    SqlEngine.SNOWFLAKE: SnowflakeSqlPlanRenderer,
    SqlEngine.DATABRICKS: DatabricksSqlPlanRenderer,
    SqlEngine.REDSHIFT: RedshiftSqlPlanRenderer,
    SqlEngine.DUCKDB: DuckDbSqlPlanRenderer,
}


def renderer_for(sql_engine: SqlEngine) -> SqlPlanRenderer:
    """Pick the dialect-specific renderer; fall back to the default renderer."""
    factory = _RENDERER_FACTORIES.get(sql_engine, DefaultSqlPlanRenderer)
    return factory()


def parse_sql_engine(name: Optional[str]) -> SqlEngine:
    """Resolve a string like ``"trino"`` (case-insensitive) to a ``SqlEngine`` enum."""
    if name is None:
        return SqlEngine.TRINO
    upper = name.upper()
    try:
        return SqlEngine[upper]
    except KeyError as exc:
        valid = ", ".join(sorted(e.name for e in SqlEngine))
        raise ValueError(f"Unknown sql_engine '{name}'. Valid: {valid}") from exc


class OracleSqlClient(SqlClient):
    """SQL client that only knows how to *render* SQL, never to run it.

    Mirrors the ``SimpleSqlClient`` in ``python_oracle/engine_wrapper.py``.
    The CLI exposes no execution endpoints, so the execution-style methods
    raise ``NotImplementedError`` defensively.
    """

    def __init__(self, sql_engine_type: SqlEngine):
        self._sql_engine_type = sql_engine_type
        self._sql_plan_renderer = renderer_for(sql_engine_type)

    @property
    def sql_engine_type(self) -> SqlEngine:
        return self._sql_engine_type

    @property
    def sql_plan_renderer(self) -> SqlPlanRenderer:
        return self._sql_plan_renderer

    def query(
        self,
        stmt: str,
        sql_bind_parameter_set: Optional[SqlBindParameterSet] = None,
    ) -> MetricFlowDataTable:
        raise NotImplementedError("Oracle CLI does not execute SQL.")

    def execute(
        self,
        stmt: str,
        sql_bind_parameter_set: Optional[SqlBindParameterSet] = None,
    ) -> None:
        raise NotImplementedError("Oracle CLI does not execute SQL.")

    def dry_run(
        self,
        stmt: str,
        sql_bind_parameter_set: Optional[SqlBindParameterSet] = None,
    ) -> None:
        raise NotImplementedError("Oracle CLI does not execute SQL.")

    def close(self) -> None:
        return None

    def render_bind_parameter_key(self, bind_parameter_key: str) -> str:
        return f":{bind_parameter_key}"


def create_engine(
    manifest: PydanticSemanticManifest,
    sql_engine: SqlEngine = SqlEngine.TRINO,
) -> MetricFlowEngine:
    """Wire ``manifest`` + a render-only ``SqlClient`` into a ``MetricFlowEngine``."""
    manifest_lookup = SemanticManifestLookup(manifest)
    sql_client = OracleSqlClient(sql_engine_type=sql_engine)
    return MetricFlowEngine(
        semantic_manifest_lookup=manifest_lookup,
        sql_client=sql_client,
    )
