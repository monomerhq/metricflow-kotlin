"""Build a :class:`PydanticSemanticManifest` from JSON-friendly dicts.

The oracle CLI accepts manifests in the same shape the metricflow YAML loader
produces after ``PydanticSemanticManifest.parse_obj``: a list of semantic-model
dicts, a list of metric dicts, a project-configuration dict, and an optional
list of saved-query dicts. We keep the names exactly as Pydantic expects them
so that fixtures can be hand-written or extracted by reading the upstream
YAMLs.
"""

from __future__ import annotations

from typing import Any, Mapping, Optional, Sequence

from metricflow_semantic_interfaces.implementations.metric import PydanticMetric
from metricflow_semantic_interfaces.implementations.project_configuration import (
    PydanticProjectConfiguration,
)
from metricflow_semantic_interfaces.implementations.saved_query import PydanticSavedQuery
from metricflow_semantic_interfaces.implementations.semantic_manifest import (
    PydanticSemanticManifest,
)
from metricflow_semantic_interfaces.implementations.semantic_model import PydanticSemanticModel
from metricflow_semantic_interfaces.transformations.semantic_manifest_transformer import (
    PydanticSemanticManifestTransformer,
)


def build_manifest(
    semantic_models: Sequence[Mapping[str, Any]],
    metrics: Sequence[Mapping[str, Any]],
    project_configuration: Mapping[str, Any],
    saved_queries: Optional[Sequence[Mapping[str, Any]]] = None,
    apply_transform: bool = True,
) -> PydanticSemanticManifest:
    """Parse the input dicts into a ``PydanticSemanticManifest``.

    Args:
        semantic_models: One dict per semantic model in the same shape that
            ``PydanticSemanticModel.parse_obj`` accepts.
        metrics: One dict per metric.
        project_configuration: Project-configuration dict (``time_spines`` or
            ``time_spine_table_configurations`` should be present for any
            non-trivial query).
        saved_queries: Optional list of saved-query dicts.
        apply_transform: If True (default), run the standard
            ``PydanticSemanticManifestTransformer`` which fills in defaults
            and runs validation-friendly rewrites. The
            ``MetricFlowEngine`` initializer expects a transformed manifest;
            we only skip the transform for the ``validate_manifest`` command
            so that the validator sees the manifest before any massaging.
    """
    manifest = PydanticSemanticManifest(
        semantic_models=[PydanticSemanticModel.parse_obj(sm) for sm in semantic_models],
        metrics=[PydanticMetric.parse_obj(m) for m in metrics],
        project_configuration=PydanticProjectConfiguration.parse_obj(project_configuration),
        saved_queries=[PydanticSavedQuery.parse_obj(sq) for sq in (saved_queries or [])],
    )
    if apply_transform:
        manifest = PydanticSemanticManifestTransformer.transform(manifest)
    return manifest


def build_manifest_from_input(
    input_data: Mapping[str, Any],
    apply_transform: bool = True,
) -> PydanticSemanticManifest:
    """Convenience: pull the four manifest fields out of an oracle-CLI input dict."""
    return build_manifest(
        semantic_models=input_data.get("semantic_models") or [],
        metrics=input_data.get("metrics") or [],
        project_configuration=input_data.get("project_configuration") or {},
        saved_queries=input_data.get("saved_queries") or [],
        apply_transform=apply_transform,
    )
