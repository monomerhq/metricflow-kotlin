# `:infrastructure:sql:render:postgres`

SQL renderer for the PostgreSQL engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.postgres.PostgresSQLSqlPlanRenderer` | `PostgresSqlPlanRenderer` (Python's doubled `SQL` is an upstream typo; we drop it) |
| `metricflow.sql.render.postgres.PostgresSqlExpressionRenderer` | `PostgresSqlExpressionRenderer` |

## Method overrides

| Method | Postgres divergence from default ANSI |
|---|---|
| `doubleDataType` | `DOUBLE PRECISION` |
| `supportedPercentileFunctionTypes` | `CONTINUOUS`, `DISCRETE` |
| `visitSubtractTimeIntervalExpr` | `arg - MAKE_INTERVAL(grans => count)` |
| `visitAddTimeExpr` | `arg + MAKE_INTERVAL(grans => CAST(count AS INTEGER))` |
| `visitGenerateUuidExpr` | `GEN_RANDOM_UUID()` |
| `visitPercentileExpr` | `PERCENTILE_CONT` / `PERCENTILE_DISC`; rejects approximate variants |

## Notable quirks

- Postgres uses `MAKE_INTERVAL(grans => n)` (using its keyword-argument syntax) rather
  than `DATEADD`. The granularity name gets pluralised (`days`, `months`, …).
- The `+ MAKE_INTERVAL(...)` form wraps the count in `CAST(... AS INTEGER)` because
  Postgres rejects non-integer arguments to keyword-arg make_interval calls.
- `QUARTER` granularity expands to `MONTH * 3` for `visitSubtractTimeIntervalExpr`. For
  `visitAddTimeExpr`, Python builds a multiply expression but never uses it (the
  rendered count remains the original `countExpr`). We preserve the bug-by-design.
- Both approximate-percentile variants are unsupported.

## Wave

W6.
