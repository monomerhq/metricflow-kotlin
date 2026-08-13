# `:infrastructure:sql:render:databricks`

SQL renderer for the Databricks engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.databricks.DatabricksSqlPlanRenderer` | `DatabricksSqlPlanRenderer` |
| `metricflow.sql.render.databricks.DatabricksSqlExpressionRenderer` | `DatabricksSqlExpressionRenderer` |

## Method overrides

| Method | Databricks divergence from default ANSI |
|---|---|
| `supportedPercentileFunctionTypes` | `CONTINUOUS`, `APPROXIMATE_DISCRETE` |
| `renderDatePart` | `DOW` → `DAYOFWEEK_ISO` |
| `visitPercentileExpr` | `PERCENTILE_CONT` (continuous) / `APPROX_PERCENTILE` (approx discrete) only |

## Notable quirks

- Discrete percentile is available only on Databricks Runtime 11.0+. The current
  upstream Python disables it; we preserve the rejection.
- Approximate-continuous percentile is also rejected — Databricks's `APPROX_PERCENTILE`
  is discrete-only.

## Wave

W6.
