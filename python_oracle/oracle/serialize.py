"""Convert metricflow domain objects to JSON-friendly dicts.

We keep the metricflow domain vocabulary on the wire (``dunder_name``,
``semantic_model_reference``, ``entity_links`` ...). Each serializer is a
small function so that Phase 1b can read these and match the shapes against
the Kotlin output.
"""

from __future__ import annotations

import dataclasses
import datetime
import enum
from typing import Any, Iterable, Mapping, Optional, Sequence

from metricflow.engine.models import Dimension, Entity, Metric, SavedQuery
from metricflow_semantics.sql.sql_bind_parameters import (
    SqlBindParameter,
    SqlBindParameterSet,
    SqlBindParameterValue,
)
from metricflow_semantic_interfaces.references import (
    EntityReference,
    SemanticModelReference,
)
from metricflow_semantic_interfaces.validations.validator_helpers import (
    SemanticManifestValidationResults,
    ValidationIssue,
    ValidationIssueLevel,
)


# ---------------------------------------------------------------------------
# generic helpers
# ---------------------------------------------------------------------------


def to_jsonable(value: Any) -> Any:
    """Best-effort recursive conversion to JSON-friendly Python types.

    Handles Pydantic models, dataclasses, enums, datetime, references, sets,
    and nested containers. Anything we don't recognize is rendered with
    ``str(value)`` so that ``json.dump`` never blows up on the happy path.
    """
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    if isinstance(value, enum.Enum):
        return value.value
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat()
    if isinstance(value, (EntityReference,)):
        return {"element_name": value.element_name}
    if isinstance(value, SemanticModelReference):
        return {"semantic_model_name": value.semantic_model_name}
    if isinstance(value, Mapping):
        return {str(k): to_jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [to_jsonable(v) for v in value]
    # Pydantic v1/v2 BaseModel
    if hasattr(value, "model_dump"):
        try:
            return to_jsonable(value.model_dump())
        except Exception:  # noqa: BLE001
            pass
    if hasattr(value, "dict") and callable(value.dict):
        try:
            return to_jsonable(value.dict())
        except Exception:  # noqa: BLE001
            pass
    if dataclasses.is_dataclass(value):
        return {
            field.name: to_jsonable(getattr(value, field.name))
            for field in dataclasses.fields(value)
        }
    return str(value)


# ---------------------------------------------------------------------------
# engine models
# ---------------------------------------------------------------------------


def dimension_to_dict(dimension: Dimension) -> dict[str, Any]:
    """Serialize a ``metricflow.engine.models.Dimension``."""
    granularity: Optional[str] = None
    if dimension.type_params is not None and dimension.type_params.time_granularity is not None:
        granularity = dimension.type_params.time_granularity.value

    semantic_model_name: Optional[str] = None
    if dimension.semantic_model_reference is not None:
        semantic_model_name = dimension.semantic_model_reference.semantic_model_name

    return {
        "name": dimension.name,
        "dunder_name": dimension.dunder_name,
        "qualified_name": dimension.dunder_name,
        "type": dimension.type.value,
        "granularity": granularity,
        "entity_links": [link.element_name for link in dimension.entity_links],
        "semantic_model_name": semantic_model_name,
        "is_partition": dimension.is_partition,
        "is_metric_time": semantic_model_name is None,
        "description": dimension.description,
        "label": dimension.label,
        "expr": dimension.expr,
        "metadata": to_jsonable(dimension.metadata),
    }


def entity_to_dict(entity: Entity) -> dict[str, Any]:
    """Serialize a ``metricflow.engine.models.Entity``."""
    return {
        "name": entity.name,
        "type": entity.type.value,
        "role": entity.role,
        "semantic_model_name": entity.semantic_model_reference.semantic_model_name,
        "description": entity.description,
        "expr": entity.expr,
    }


def metric_to_dict(metric: Metric) -> dict[str, Any]:
    """Serialize a ``metricflow.engine.models.Metric``."""
    return {
        "name": metric.name,
        "type": metric.type.value,
        "description": metric.description,
        "label": metric.label,
        "type_params": to_jsonable(metric.type_params),
        "filter": to_jsonable(metric.filter),
        "metadata": to_jsonable(metric.metadata),
        "config": to_jsonable(metric.config),
        "dimensions": [dimension_to_dict(d) for d in metric.dimensions],
        "semantic_models": [m.semantic_model_name for m in metric.semantic_models],
    }


def saved_query_to_dict(saved_query: SavedQuery) -> dict[str, Any]:
    """Serialize a ``metricflow.engine.models.SavedQuery``."""
    return {
        "name": saved_query.name,
        "description": saved_query.description,
        "label": saved_query.label,
        "query_params": to_jsonable(saved_query.query_params),
        "metadata": to_jsonable(saved_query.metadata),
        "exports": [to_jsonable(e) for e in saved_query.exports],
        "tags": list(saved_query.tags),
    }


# ---------------------------------------------------------------------------
# SQL plumbing
# ---------------------------------------------------------------------------


def bind_parameter_value_to_dict(value: SqlBindParameterValue) -> dict[str, Any]:
    """Render a ``SqlBindParameterValue`` as a tagged-union-style dict."""
    union = value.union_value
    return {
        "value": to_jsonable(union),
        "python_type": type(union).__name__,
    }


def bind_parameter_to_dict(param: SqlBindParameter) -> dict[str, Any]:
    """Render a single ``SqlBindParameter``."""
    return {"key": param.key, "value": bind_parameter_value_to_dict(param.value)}


def bind_parameter_set_to_dict(param_set: SqlBindParameterSet) -> dict[str, Any]:
    """Render a ``SqlBindParameterSet`` as ``{"param_items": [...]}``."""
    return {"param_items": [bind_parameter_to_dict(p) for p in param_set.param_items]}


# ---------------------------------------------------------------------------
# validation
# ---------------------------------------------------------------------------


def issue_to_dict(issue: ValidationIssue) -> dict[str, Any]:
    """Serialize a single ``ValidationIssue`` (warning / future-error / error)."""
    context_obj: Any = None
    if issue.context is not None:
        context_obj = to_jsonable(issue.context)
    out: dict[str, Any] = {
        "level": issue.level.name,
        "message": issue.message,
        "context": context_obj,
        "context_str": issue.context.context_str() if issue.context is not None else "",
        "extra_detail": issue.extra_detail,
        "readable": issue.as_readable_str(verbose=False),
    }
    if issue.level is ValidationIssueLevel.FUTURE_ERROR:
        error_date = getattr(issue, "error_date", None)
        if error_date is not None:
            out["error_date"] = to_jsonable(error_date)
    return out


def validation_results_to_dict(results: SemanticManifestValidationResults) -> dict[str, Any]:
    """Serialize the aggregate validator output."""
    issues = list(results.all_issues)
    return {
        "issues": [issue_to_dict(issue) for issue in issues],
        "error_count": len(results.errors),
        "future_error_count": len(results.future_errors),
        "warning_count": len(results.warnings),
        "has_blocking_issues": results.has_blocking_issues,
    }
