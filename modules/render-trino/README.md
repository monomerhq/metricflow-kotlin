# `:infrastructure:sql:render:trino`

SQL renderer for the Trino engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.trino.TrinoSqlPlanRenderer` | `TrinoSqlPlanRenderer` |
| `metricflow.sql.render.trino.TrinoSqlExpressionRenderer` | `TrinoSqlExpressionRenderer` |

## Method overrides

| Method | Trino divergence from default ANSI |
|---|---|
| `supportedPercentileFunctionTypes` | only `APPROXIMATE_CONTINUOUS` |
| `visitGenerateUuidExpr` | `uuid()` (lowercase) |
| `visitSubtractTimeIntervalExpr` | `DATE_ADD('<gran>', -count, arg)` — quoted granularity, function-name change |
| `visitAddTimeExpr` | same `DATE_ADD(...)` style for additions |
| `visitPercentileExpr` | uses `approx_percentile`; rejects continuous/discrete |
| `visitBetweenExpr` | wraps timestamp literals with `timestamp` prefix |
| `renderDatePart` | `DOW` → `DAY_OF_WEEK` |

## Notable quirks

- `visitBetweenExpr` uses Python's `dateutil.parser.parse(...)` to *guess* whether the
  start expression is a timestamp literal — see [`TrinoSqlExpressionRenderer.looksLikeTimestampLiteral`](src/main/kotlin/cc/monomer/metricflow/infrastructure/sql/render/trino/TrinoSqlExpressionRenderer.kt)
  for the Kotlin approximation. The behaviour is faithful for ISO-like quoted literals
  (the only inputs that exercise the path in practice).
- `visitAddTimeExpr`: Python builds a multiply-by-3 expression for `QUARTER` but
  drops the result on the floor (never assigns it back). Same bug-by-design preserved.

## Wave

W6.
