from __future__ import annotations

import datetime
import json
import logging
from dataclasses import dataclass
from typing import Optional, Sequence

from metricflow_semantic_interfaces.implementations.semantic_manifest import (
    PydanticSemanticManifest,
)
from metricflow_semantic_interfaces.implementations.metric import PydanticMetric
from metricflow_semantic_interfaces.implementations.project_configuration import (
    PydanticProjectConfiguration,
)
from metricflow_semantic_interfaces.implementations.semantic_model import (
    PydanticSemanticModel,
)
from metricflow_semantic_interfaces.protocols import SemanticManifest
from metricflow_semantic_interfaces.transformations.semantic_manifest_transformer import (
    PydanticSemanticManifestTransformer,
)
from metricflow.data_table.mf_table import MetricFlowDataTable
from metricflow.engine.models import Dimension, Entity
from metricflow.engine.metricflow_engine import MetricFlowEngine, MetricFlowQueryRequest
from metricflow.protocols.sql_client import SqlClient, SqlEngine
from metricflow.sql.render.big_query import BigQuerySqlPlanRenderer
from metricflow.sql.render.databricks import DatabricksSqlPlanRenderer
from metricflow.sql.render.duckdb_renderer import DuckDbSqlPlanRenderer
from metricflow.sql.render.postgres import PostgresSQLSqlPlanRenderer
from metricflow.sql.render.redshift import RedshiftSqlPlanRenderer
from metricflow.sql.render.snowflake import SnowflakeSqlPlanRenderer
from metricflow.sql.render.sql_plan_renderer import DefaultSqlPlanRenderer, SqlPlanRenderer
from metricflow.sql.render.trino import TrinoSqlPlanRenderer
from metricflow_semantics.model.semantic_manifest_lookup import SemanticManifestLookup
from metricflow_semantics.sql.sql_bind_parameters import SqlBindParameterSet

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class GroupByDimension:
    semantic_model_name: str   # empty string for metric_time dimensions
    name: str                  # qualified name, e.g. "event_id__event_ts" or "metric_time__day"
    type: str                  # "categorical" | "time"
    granularity: Optional[str] # e.g. "day", "week", "month" — present when type="time"
    is_metric_time: bool       # True for MetricFlow built-in time-spine dimensions


@dataclass(frozen=True, slots=True)
class GroupByEntity:
    semantic_model_name: str
    name: str


@dataclass(frozen=True, slots=True)
class GroupBys:
    dimensions: list[GroupByDimension]
    entities: list[GroupByEntity]


class SimpleSqlClient(SqlClient):
    def __init__(self, sql_engine_type: SqlEngine = SqlEngine.TRINO):
        self._sql_engine_type = sql_engine_type
        self._sql_plan_renderer = self._renderer_for_engine(sql_engine_type)

    @staticmethod
    def _renderer_for_engine(sql_engine: SqlEngine) -> SqlPlanRenderer:
        renderer_factories: dict[SqlEngine, type[SqlPlanRenderer]] = {
            SqlEngine.TRINO: TrinoSqlPlanRenderer,
            SqlEngine.POSTGRES: PostgresSQLSqlPlanRenderer,
            SqlEngine.BIGQUERY: BigQuerySqlPlanRenderer,
            SqlEngine.SNOWFLAKE: SnowflakeSqlPlanRenderer,
            SqlEngine.DATABRICKS: DatabricksSqlPlanRenderer,
            SqlEngine.REDSHIFT: RedshiftSqlPlanRenderer,
            SqlEngine.DUCKDB: DuckDbSqlPlanRenderer,
        }
        renderer_factory = renderer_factories.get(sql_engine, DefaultSqlPlanRenderer)
        return renderer_factory()

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
        raise NotImplementedError("Query execution is not supported by SimpleSqlClient.")

    def execute(
        self,
        stmt: str,
        sql_bind_parameter_set: Optional[SqlBindParameterSet] = None,
    ) -> None:
        raise NotImplementedError("Statement execution is not supported by SimpleSqlClient.")

    def dry_run(
        self,
        stmt: str,
        sql_bind_parameter_set: Optional[SqlBindParameterSet] = None,
    ) -> None:
        raise NotImplementedError("Dry run is not supported by SimpleSqlClient.")

    def close(self) -> None:
        return None

    def render_bind_parameter_key(self, bind_parameter_key: str) -> str:
        return f":{bind_parameter_key}"


class MetricFlowSqlEngine:
    def __init__(
        self,
        semantic_models_json: Sequence[str],
        metrics_json: Sequence[str],
        project_config_json: str,
        sql_engine: SqlEngine = SqlEngine.TRINO,
    ) -> None:
        self._manifest = self._create_manifest_from_json(
            semantic_models_json=semantic_models_json,
            metrics_json=metrics_json,
            project_config_json=project_config_json,
        )
        self._engine: MetricFlowEngine = self._initialize_engine(sql_engine)

    @staticmethod
    def _load_json_documents(raw_documents: Sequence[str], resource_name: str) -> list[dict]:
        parsed_documents: list[dict] = []
        for index, raw_document in enumerate(raw_documents):
            try:
                parsed_document = json.loads(raw_document)
            except json.JSONDecodeError as exc:
                raise ValueError(
                    f"Invalid {resource_name} JSON at index {index}: {exc.msg}"
                ) from exc
            if not isinstance(parsed_document, dict):
                raise ValueError(
                    f"Invalid {resource_name} JSON at index {index}: expected object."
                )
            parsed_documents.append(parsed_document)
        return parsed_documents

    @staticmethod
    def _load_project_config(raw_document: str) -> dict:
        try:
            parsed_document = json.loads(raw_document)
        except json.JSONDecodeError as exc:
            raise ValueError(f"Invalid project configuration JSON: {exc.msg}") from exc
        if not isinstance(parsed_document, dict):
            raise ValueError("Invalid project configuration JSON: expected object.")
        return parsed_document

    @staticmethod
    def _create_manifest_from_json(
        semantic_models_json: Sequence[str],
        metrics_json: Sequence[str],
        project_config_json: str,
    ) -> PydanticSemanticManifest:
        logger.info(
            "Creating semantic manifest from %d semantic models and %d metrics",
            len(semantic_models_json),
            len(metrics_json),
        )

        semantic_models_dicts = MetricFlowSqlEngine._load_json_documents(
            raw_documents=semantic_models_json,
            resource_name="semantic model",
        )
        metrics_dicts = MetricFlowSqlEngine._load_json_documents(
            raw_documents=metrics_json,
            resource_name="metric",
        )
        project_config_dict = MetricFlowSqlEngine._load_project_config(project_config_json)

        semantic_models = [
            PydanticSemanticModel.parse_obj(sm_dict)
            for sm_dict in semantic_models_dicts
        ]
        metrics = [
            PydanticMetric.parse_obj(m_dict)
            for m_dict in metrics_dicts
        ]
        project_config = PydanticProjectConfiguration.parse_obj(project_config_dict)

        manifest = PydanticSemanticManifest(
            semantic_models=semantic_models,
            metrics=metrics,
            project_configuration=project_config,
        )

        return PydanticSemanticManifestTransformer.transform(manifest)

    @staticmethod
    def _resolve_time_constraint(
        raw_value: Optional[str],
        default_value: datetime.datetime,
    ) -> datetime.datetime:
        if not raw_value:
            return default_value
        try:
            return datetime.datetime.fromisoformat(raw_value)
        except ValueError as exc:
            raise ValueError(
                f"Invalid ISO datetime value: {raw_value}"
            ) from exc

    def _initialize_engine(self, sql_engine: SqlEngine) -> MetricFlowEngine:
        manifest_lookup = SemanticManifestLookup(self._manifest)
        sql_client = SimpleSqlClient(sql_engine_type=sql_engine)
        return MetricFlowEngine(
            semantic_manifest_lookup=manifest_lookup,
            sql_client=sql_client,
        )

    def generate_sql(
        self,
        metrics: Sequence[str],
        group_by: Optional[Sequence[str]] = None,
        where: Optional[Sequence[str]] = None,
        order_by: Optional[Sequence[str]] = None,
        limit: Optional[int] = None,
        time_constraint_start: Optional[str] = None,
        time_constraint_end: Optional[str] = None,
    ) -> str:
        if not metrics:
            raise ValueError("At least one metric is required to generate SQL.")

        now = datetime.datetime.now()
        resolved_time_start = self._resolve_time_constraint(
            raw_value=time_constraint_start,
            default_value=now - datetime.timedelta(days=30),
        )
        resolved_time_end = self._resolve_time_constraint(
            raw_value=time_constraint_end,
            default_value=now,
        )

        request = MetricFlowQueryRequest.create_with_random_request_id(
            metric_names=list(metrics) if metrics else None,
            group_by_names=list(group_by) if group_by else None,
            limit=limit,
            time_constraint_start=resolved_time_start,
            time_constraint_end=resolved_time_end,
            where_constraints=list(where) if where else None,
            order_by_names=list(order_by) if order_by else None,
        )

        result = self._engine.explain(request)
        return result.sql_statement.sql

    def list_group_bys(self, metrics: Sequence[str]) -> GroupBys:
        if not metrics:
            logger.debug("list_group_bys called with no metrics, returning empty result")
            return GroupBys(dimensions=[], entities=[])

        logger.info("Listing group-bys for metrics: %s", list(metrics))
        group_by_items = self._engine.list_group_bys(metric_names=list(metrics))

        dimensions: list[GroupByDimension] = []
        entities: list[GroupByEntity] = []

        for group_by in group_by_items:
            if isinstance(group_by, Dimension):
                dim_type = group_by.type.value  # "categorical" | "time"
                granularity: Optional[str] = None
                if group_by.type_params is not None:
                    granularity = group_by.type_params.time_granularity.value if group_by.type_params.time_granularity is not None else None

                if group_by.semantic_model_reference is None:
                    # metric_time — MetricFlow built-in time-spine dimension, no owning semantic model
                    logger.debug(
                        "Including metric_time dimension: name=%s type=%s granularity=%s",
                        group_by.qualified_name, dim_type, granularity,
                    )
                    dimensions.append(
                        GroupByDimension(
                            semantic_model_name="",
                            name=group_by.qualified_name,
                            type=dim_type,
                            granularity=granularity,
                            is_metric_time=True,
                        )
                    )
                else:
                    logger.debug(
                        "Including dimension: model=%s name=%s type=%s granularity=%s",
                        group_by.semantic_model_reference.semantic_model_name,
                        group_by.qualified_name, dim_type, granularity,
                    )
                    dimensions.append(
                        GroupByDimension(
                            semantic_model_name=group_by.semantic_model_reference.semantic_model_name,
                            name=group_by.qualified_name,
                            type=dim_type,
                            granularity=granularity,
                            is_metric_time=False,
                        )
                    )
                continue

            if isinstance(group_by, Entity):
                if group_by.semantic_model_reference is None:
                    logger.warning(
                        "Skipping entity without semantic model reference: name=%s type=%s",
                        group_by.name, group_by.type.value,
                    )
                    continue
                logger.debug(
                    "Including entity: model=%s name=%s type=%s",
                    group_by.semantic_model_reference.semantic_model_name,
                    group_by.name, group_by.type.value,
                )
                entities.append(
                    GroupByEntity(
                        semantic_model_name=group_by.semantic_model_reference.semantic_model_name,
                        name=group_by.name,
                    )
                )
                continue

            logger.warning("Skipping unknown group-by type: %s", type(group_by).__name__)

        logger.info(
            "list_group_bys result: %d dimensions (%d metric_time), %d entities",
            len(dimensions),
            sum(1 for d in dimensions if d.is_metric_time),
            len(entities),
        )
        return GroupBys(dimensions=dimensions, entities=entities)
