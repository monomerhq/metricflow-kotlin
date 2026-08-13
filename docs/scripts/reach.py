"""Reachability tracer v2 — adds dialect renderers and transformer pipeline."""
import ast
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parents[2] / "python_oracle" / "upstream"
PACKAGES = ("metricflow", "metricflow_semantics", "metricflow_semantic_interfaces")

mod_to_file = {}
file_to_mod = {}
for pkg in PACKAGES:
    for path in (ROOT / pkg).rglob("*.py"):
        rel = path.relative_to(ROOT)
        parts = list(rel.parts)
        if parts[-1] == "__init__.py":
            parts = parts[:-1]
        else:
            parts[-1] = parts[-1][:-3]
        mod = ".".join(parts)
        mod_to_file[mod] = path
        file_to_mod[path] = mod

# Execution-only modules: reach into them does not extend our scope.
# These are pruned from the reachable set after the trace.
EXECUTION_PRUNE = {
    "metricflow.execution.executor",  # SequentialPlanExecutor — pure execution
}
# Note: metricflow.execution.{convert_to_execution_plan, dataflow_to_execution,
# execution_plan} are reached via explain() but their *role* is to wrap the
# rendered SQL into an execution-plan task. For Kotlin we'll flatten those —
# in scope.md we classify them as "reachable but execution-flavored".

# Entry modules. Per orchestrator PORT SCOPE:
# - All 7 MetricFlowEngine SQL-generation methods (entry: engine module)
# - SemanticManifestValidator.checked_validations
# - All dialect renderers (PORT SCOPE explicit: trino, bigquery, snowflake, databricks, redshift, duckdb, postgres, default)
# - PydanticSemanticManifestTransformer (engine_wrapper applies it before constructing manifest;
#   any production caller will need it)
ENTRY_MODULES = {
    "metricflow.engine.metricflow_engine",
    "metricflow_semantic_interfaces.validations.semantic_manifest_validator",
    # Dialect renderers (explicit port targets):
    "metricflow.sql.render.big_query",
    "metricflow.sql.render.databricks",
    "metricflow.sql.render.duckdb_renderer",
    "metricflow.sql.render.postgres",
    "metricflow.sql.render.redshift",
    "metricflow.sql.render.snowflake",
    "metricflow.sql.render.trino",
    "metricflow.sql.render.sql_plan_renderer",  # default + base
    # Manifest transformer (wraps rule pipeline; required for production manifest hydration):
    "metricflow_semantic_interfaces.transformations.semantic_manifest_transformer",
    "metricflow_semantic_interfaces.transformations.pydantic_rule_set",
    # Engine models package facade:
    "metricflow.engine.models",
}

def imports_in(path):
    try:
        tree = ast.parse(path.read_text(), filename=str(path))
    except SyntaxError:
        return []
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                out.append(alias.name)
        elif isinstance(node, ast.ImportFrom):
            mod = node.module or ""
            level = node.level
            if level == 0 and mod:
                out.append(mod)
                for alias in node.names:
                    out.append(f"{mod}.{alias.name}")
            elif level > 0:
                pkg_parts = file_to_mod[path].split(".")
                base = ".".join(pkg_parts[:-level])
                target = base + ("." + mod if mod else "")
                out.append(target)
                for alias in node.names:
                    out.append(f"{target}.{alias.name}")
    return out

reachable = set()
queue = list(ENTRY_MODULES)
while queue:
    mod = queue.pop()
    if mod in reachable:
        continue
    if mod not in mod_to_file:
        continue
    reachable.add(mod)
    for imp in imports_in(mod_to_file[mod]):
        parts = imp.split(".")
        for k in range(len(parts), 0, -1):
            cand = ".".join(parts[:k])
            if cand in mod_to_file and cand not in reachable:
                queue.append(cand)
                break

# Prune executor.py from reachable, regardless of how it got there.
for m in EXECUTION_PRUNE:
    reachable.discard(m)

reachable_files = {mod_to_file[m] for m in reachable}

excl_execution = set()
for mod, path in mod_to_file.items():
    if mod in EXECUTION_PRUNE:
        excl_execution.add(path)

all_files = set(file_to_mod.keys())
other_unreachable = all_files - reachable_files - excl_execution

def loc(p): return len(p.read_text().splitlines())
total_loc = sum(loc(p) for p in all_files)
reach_loc = sum(loc(p) for p in reachable_files)
exec_loc = sum(loc(p) for p in excl_execution)
unr_loc = sum(loc(p) for p in other_unreachable)

print(f"TOTAL files={len(all_files)} loc={total_loc}")
print(f"REACHABLE files={len(reachable_files)} loc={reach_loc}")
print(f"EXEC-EXCLUDED files={len(excl_execution)} loc={exec_loc}")
print(f"OTHER-UNREACHABLE files={len(other_unreachable)} loc={unr_loc}")
print(f"SUM-CHECK: {reach_loc + exec_loc + unr_loc} == {total_loc} -> {reach_loc + exec_loc + unr_loc == total_loc}")

# Per-package summary (reachable)
print("\n=== REACHABLE per package ===")
pkg_tally = defaultdict(lambda: [0, 0])
for p in reachable_files:
    rel = p.relative_to(ROOT)
    pkg = rel.parts[0]
    pkg_tally[pkg][0] += 1
    pkg_tally[pkg][1] += loc(p)
for pkg, (n, l) in sorted(pkg_tally.items()):
    print(f"  {pkg}  files={n}  loc={l}")

# Per-package, per-second-level dir
print("\n=== REACHABLE per second-level dir ===")
dir_tally = defaultdict(lambda: [0, 0])
for p in reachable_files:
    rel = p.relative_to(ROOT)
    d = "/".join(rel.parts[:2]) if len(rel.parts) >= 2 else rel.parts[0]
    if len(rel.parts) == 1:
        d = rel.parts[0]
    elif len(rel.parts) == 2 and rel.parts[1].endswith(".py"):
        d = rel.parts[0] + "/<root>"
    else:
        d = "/".join(rel.parts[:2])
    dir_tally[d][0] += 1
    dir_tally[d][1] += loc(p)
for d, (n, l) in sorted(dir_tally.items()):
    print(f"  {d:55s} files={n:4d}  loc={l}")

print("\n=== EXEC-EXCLUDED ===")
for p in sorted(excl_execution):
    print(f"  {p.relative_to(ROOT)}  ({loc(p)} loc)")

print("\n=== OTHER UNREACHABLE summary by second-level dir ===")
unreach_tally = defaultdict(lambda: [0, 0])
for p in other_unreachable:
    rel = p.relative_to(ROOT)
    if len(rel.parts) == 1:
        d = rel.parts[0]
    elif len(rel.parts) == 2 and rel.parts[1].endswith(".py"):
        d = rel.parts[0] + "/<root>"
    else:
        d = "/".join(rel.parts[:2])
    unreach_tally[d][0] += 1
    unreach_tally[d][1] += loc(p)
for d, (n, l) in sorted(unreach_tally.items(), key=lambda x: -x[1][1]):
    print(f"  {d:55s} files={n:4d}  loc={l}")

print("\n=== TOP UNREACHABLE files by LOC ===")
for p in sorted(other_unreachable, key=lambda x: -loc(x))[:40]:
    print(f"  {p.relative_to(ROOT)} ({loc(p)} loc)")

# Also dump reachable sorted
Path("/tmp/reachable.txt").write_text("\n".join(str(p.relative_to(ROOT)) for p in sorted(reachable_files)))
Path("/tmp/unreachable.txt").write_text("\n".join(str(p.relative_to(ROOT)) for p in sorted(other_unreachable)))
Path("/tmp/exec_excluded.txt").write_text("\n".join(str(p.relative_to(ROOT)) for p in sorted(excl_execution)))

# Per-second-level for reachable (used in dependency-dag.md)
import json
# emit per-third-level for finer-grained module grouping
Path("/tmp/reach_summary.json").write_text(json.dumps({
    "total_loc": total_loc, "reach_loc": reach_loc, "exec_loc": exec_loc, "unr_loc": unr_loc,
    "total_files": len(all_files), "reach_files": len(reachable_files),
    "exec_files": len(excl_execution), "unr_files": len(other_unreachable),
}, indent=2))
