"""Smoke tests: one test per oracle subcommand.

Each test calls the command's ``run(dict) -> dict`` function directly and
checks the shape of the output. The point isn't to validate metricflow's
behaviour (the upstream tests cover that); it's to make sure our wrappers
import, accept the minimal manifest fixtures, and produce the JSON shape that
the SCHEMA contract promises.
"""

from __future__ import annotations

import copy
import json
import os
from typing import Any

import pytest

from oracle.commands import COMMANDS

_FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def _load_fixture(name: str) -> dict[str, Any]:
    with open(os.path.join(_FIXTURES_DIR, name)) as fh:
        return json.load(fh)


@pytest.fixture
def valid_manifest() -> dict[str, Any]:
    return _load_fixture("minimal_valid_manifest.json")


@pytest.fixture
def invalid_manifest() -> dict[str, Any]:
    return _load_fixture("minimal_invalid_manifest.json")


def _with_args(manifest: dict[str, Any], args: dict[str, Any]) -> dict[str, Any]:
    return {**copy.deepcopy(manifest), "args": args}


# ---------------------------------------------------------------------------
# explain
# ---------------------------------------------------------------------------


def test_explain_returns_sql(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["explain"](
        _with_args(
            valid_manifest,
            {"metric_names": ["bookings"], "group_by_names": ["metric_time__day"]},
        )
    )
    assert isinstance(out["sql"], str)
    assert len(out["sql"]) > 0
    assert "bookings" in out["sql"].lower()
    assert "bind_parameters" in out
    assert "metadata" in out


# ---------------------------------------------------------------------------
# list_metrics
# ---------------------------------------------------------------------------


def test_list_metrics(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_metrics"](_with_args(valid_manifest, {"include_dimensions": True}))
    assert "metrics" in out
    names = [m["name"] for m in out["metrics"]]
    assert "bookings" in names


def test_list_metrics_without_dimensions(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_metrics"](_with_args(valid_manifest, {"include_dimensions": False}))
    metric = next(m for m in out["metrics"] if m["name"] == "bookings")
    assert metric["dimensions"] == []


# ---------------------------------------------------------------------------
# list_dimensions
# ---------------------------------------------------------------------------


def test_list_dimensions_no_metric_filter(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_dimensions"](_with_args(valid_manifest, {}))
    assert "dimensions" in out
    assert any(d["name"] == "ds" for d in out["dimensions"])


def test_list_dimensions_with_metric_filter(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_dimensions"](_with_args(valid_manifest, {"metric_names": ["bookings"]}))
    assert "dimensions" in out


# ---------------------------------------------------------------------------
# entities_for_metrics
# ---------------------------------------------------------------------------


def test_entities_for_metrics(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["entities_for_metrics"](
        _with_args(valid_manifest, {"metric_names": ["bookings"]})
    )
    assert "entities" in out
    assert isinstance(out["entities"], list)


# ---------------------------------------------------------------------------
# list_group_bys
# ---------------------------------------------------------------------------


def test_list_group_bys(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_group_bys"](_with_args(valid_manifest, {"metric_names": ["bookings"]}))
    assert "dimensions" in out
    assert "entities" in out
    dim_names = {d["dunder_name"] for d in out["dimensions"]}
    assert "metric_time__day" in dim_names


# ---------------------------------------------------------------------------
# list_saved_queries
# ---------------------------------------------------------------------------


def test_list_saved_queries_empty(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["list_saved_queries"](_with_args(valid_manifest, {}))
    assert out == {"saved_queries": []}


# ---------------------------------------------------------------------------
# explain_get_dimension_values
# ---------------------------------------------------------------------------


def test_explain_get_dimension_values(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["explain_get_dimension_values"](
        _with_args(
            valid_manifest,
            {"metric_names": ["bookings"], "get_group_by_values": "metric_time__day"},
        )
    )
    assert isinstance(out["sql"], str)
    assert len(out["sql"]) > 0
    assert "metric_time__day" in out["sql"]


# ---------------------------------------------------------------------------
# validate_manifest
# ---------------------------------------------------------------------------


def test_validate_manifest_valid_fixture(valid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["validate_manifest"](valid_manifest)
    assert out["issues"] == []
    assert out["error_count"] == 0
    assert out["has_blocking_issues"] is False


def test_validate_manifest_invalid_fixture(invalid_manifest: dict[str, Any]) -> None:
    out = COMMANDS["validate_manifest"](invalid_manifest)
    assert out["error_count"] >= 1
    assert out["has_blocking_issues"] is True
    levels = {issue["level"] for issue in out["issues"]}
    assert "ERROR" in levels
