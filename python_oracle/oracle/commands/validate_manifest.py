"""``validate_manifest`` — run ``SemanticManifestValidator`` and return issues.

We call ``validate_semantic_manifest`` rather than ``checked_validations``
because we always want the issue list back (the latter raises on blocking
errors and returns nothing).

The validator runs against the *transformed* manifest in production: that is
how the engine sees it. We do the same here so warning/error counts match
what a metricflow query call would surface.
"""

from __future__ import annotations

from typing import Any, Mapping

from metricflow_semantic_interfaces.validations.semantic_manifest_validator import (
    SemanticManifestValidator,
)

from oracle.manifest import build_manifest_from_input
from oracle.serialize import validation_results_to_dict


def run(input_data: Mapping[str, Any]) -> dict[str, Any]:
    """No ``args`` block; the manifest fields themselves are the input."""
    manifest = build_manifest_from_input(input_data, apply_transform=True)
    validator: SemanticManifestValidator = SemanticManifestValidator()
    results = validator.validate_semantic_manifest(manifest)
    return validation_results_to_dict(results)
