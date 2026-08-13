# `:infrastructure:sql:render:duckdb`

SQL renderer for the DuckDB engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.duckdb_renderer.DuckDbSqlPlanRenderer` | `DuckDbSqlPlanRenderer` |
| `metricflow.sql.render.duckdb_renderer.DuckDbSqlExpressionRenderer` | `DuckDbSqlExpressionRenderer` |

## Method overrides

| Method | DuckDB divergence from default ANSI |
|---|---|
| `supportedPercentileFunctionTypes` | `CONTINUOUS`, `DISCRETE`, `APPROXIMATE_CONTINUOUS` |
| `visitSubtractTimeIntervalExpr` | `arg - INTERVAL count gran` |
| `visitAddTimeExpr` | `arg + INTERVAL count gran` |
| `visitGenerateUuidExpr` | `GEN_RANDOM_UUID()` |
| `visitPercentileExpr` | `PERCENTILE_CONT` / `PERCENTILE_DISC` / `approx_quantile` |

## Notable quirks

- DuckDB uses native `INTERVAL` arithmetic via `+` / `-` operators instead of `DATEADD`.
- `approx_quantile` is lowercase (DuckDB convention).
- `APPROXIMATE_DISCRETE` is rejected.
- `QUARTER` granularity expands to `MONTH * 3` (DuckDB's `INTERVAL n quarter` is
  unreliable across versions).

## Wave

W6.
