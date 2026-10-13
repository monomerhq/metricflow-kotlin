"""Reproduce the 0.2.11 planner fixtures and SQL baselines using the Python oracle.

Run with python_oracle/.venv/bin/python. This only writes the new planner corpus;
upstream reference files and existing baselines remain untouched.
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from harness.extract_corpus import CorpusCase, extract_one


def dimension(name, kind="categorical", expr=None):
    result = {"name": name, "type": kind, "expr": expr or name}
    if kind == "time":
        result["type_params"] = {"time_granularity": "day"}
    return result


def model(name, primary, time=None, dimensions=(), measures=()):
    result = {
        "name": name,
        "node_relation": {"schema_name": "acme", "alias": name},
        "entities": [{"name": primary, "type": "primary", "expr": "row_id"},
                     {"name": "account", "type": "foreign", "expr": "account_id"}],
        "dimensions": list(dimensions), "measures": list(measures),
    }
    if time:
        result["defaults"] = {"agg_time_dimension": time}
    return result


def measure(name, expr, choice="max", groupings=()):
    return {"name": name, "expr": expr, "agg": "sum",
            "non_additive_dimension": {"name": "snapshot_date", "window_choice": choice,
                                       "window_groupings": list(groupings)}}


def simple(name, input_name):
    return {"name": name, "type": "simple", "type_params": {"measure": {"name": input_name}}}


def fixture():
    metrics = [simple("daily_active_accounts", "distinct_active_accounts")]
    for name, field in [("active_accounts", "active_users"), ("core_accounts", "core_actions")]:
        metrics.append({"name": name, "type": "cumulative",
                        "type_params": {"cumulative_type_params": {
                            "metric": {"name": "daily_active_accounts"},
                            "window": {"count": 7, "granularity": "day"}, "period_agg": "last"}},
                        "filter": {"where_filters": [{"where_sql_template":
                            "{{ Dimension('account_day__%s') }} >= 1" % field}]}})
    measures = [measure("account_mrr", "mrr_usd"),
                measure("paying_account_count", "CASE WHEN mrr_usd > 0 THEN 1 ELSE 0 END"),
                measure("prior_paid_mrr", "CASE WHEN prior_mrr > 0 THEN prior_mrr ELSE 0 END"),
                measure("retained_mrr", "CASE WHEN prior_mrr > 0 THEN mrr_usd ELSE 0 END"),
                measure("capped_mrr", "CASE WHEN prior_mrr > 0 THEN LEAST(mrr_usd, prior_mrr) ELSE 0 END"),
                measure("first_mrr", "mrr_usd", "min"),
                {"name": "all_mrr", "expr": "mrr_usd", "agg": "sum"}]
    for name, input_name in [("mrr", "account_mrr"), ("paid_accounts", "paying_account_count"),
                             ("prior_mrr", "prior_paid_mrr"), ("retained", "retained_mrr"),
                             ("capped", "capped_mrr"), ("first", "first_mrr"), ("all_snapshots", "all_mrr")]:
        metrics.append(simple(name, input_name))
    for name, numerator, denominator in [("arpa", "mrr", "paid_accounts"),
                                         ("nrr", "retained", "prior_mrr"), ("grr", "capped", "prior_mrr"),
                                         ("last_to_first", "mrr", "first"),
                                         ("last_to_all", "mrr", "all_snapshots")]:
        metrics.append({"name": name, "type": "ratio", "type_params": {
            "numerator": {"name": numerator}, "denominator": {"name": denominator}}})
    metrics.extend([simple("per_account_last", "per_account_last_value"),
                    simple("per_account_first", "per_account_first_value")])
    return {
        "semantic_models": [
            {"name": "accounts", "node_relation": {"schema_name": "acme", "alias": "accounts"},
             "entities": [{"name": "account", "type": "primary", "expr": "account_id"}],
             "dimensions": [dimension("region")], "measures": []},
            model("daily_account_activity", "account_day", "activity_date",
                  [dimension("activity_date", "time"), dimension("plan"),
                   dimension("active_users"), dimension("core_actions")],
                  [{"name": "distinct_active_accounts", "expr": "account_id", "agg": "count_distinct"}]),
            model("monthly_account_mrr", "account_month", "snapshot_date",
                  [dimension("snapshot_date", "time", "month")], measures),
            model("irregular_snapshots", "snapshot", "snapshot_date", [dimension("snapshot_date", "time")],
                  [measure("per_account_last_value", "value", "max", ["account"]),
                   measure("per_account_first_value", "value", "min", ["account"])])],
        "metrics": metrics,
        "project_configuration": {"time_spine_table_configurations": [
            {"location": "acme.time_spine_day", "column_name": "date_day", "grain": "day"}]},
        "saved_queries": [],
    }


def main():
    sys.path.insert(0, str(ROOT / "python_oracle"))
    from oracle.manifest import build_manifest_from_input
    # Corpus graph tests consume transformed manifests, as do the existing fixtures.
    manifest = json.loads(build_manifest_from_input(fixture()).json())
    (ROOT / "corpus/manifests/planner_regressions.json").write_text(json.dumps(manifest, indent=2) + "\n")
    cases = [
        ("cumulative_region_month", "active_accounts", ["metric_time__month"],
         ["{{ Dimension('account__region') }} = 'North America'"]),
        ("cumulative_region_plan_week", "active_accounts", ["metric_time__week", "account_day__plan"],
         ["{{ Dimension('account__region') }} = 'North America'", "{{ Dimension('account_day__plan') }} = 'Team'"]),
        ("snapshot_total", "mrr", [], []),
        ("snapshot_region", "mrr", ["account__region"], []),
        ("snapshot_month", "mrr", ["metric_time__month"], []),
        ("snapshot_region_month", "mrr", ["account__region", "metric_time__month"], []),
        ("snapshot_min", "first", [], []),
        ("snapshot_window_grouping", "per_account_last", [], []),
        ("snapshot_ratio", "arpa", [], []),
        ("snapshot_different_choices", "last_to_first", [], []),
    ]
    for name, metric, groups, filters in cases:
        case = CorpusCase(case_id="explain__planner_regressions__" + name,
                          subcommand="explain", manifest_name="planner_regressions",
                          args={"metric_names": [metric], "group_by_names": groups,
                                "where_constraints": filters,
                                "time_constraint_start": "2025-10-13", "time_constraint_end": "2026-10-12"},
                          dialects=("DuckDB",), source_test="PlannerDuckDbRegressionTest",
                          notes="0.2.11 planner regression; immutable upstream oracle baseline.")
        status, detail = extract_one(case)
        print(status, detail)
        if status != "OK_SQL":
            raise RuntimeError(detail)


if __name__ == "__main__":
    main()
