# `:infrastructure:sql:render:snowflake`

SQL renderer for the Snowflake engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.snowflake.SnowflakeSqlPlanRenderer` | `SnowflakeSqlPlanRenderer` |
| `metricflow.sql.render.snowflake.SnowflakeSqlExpressionRenderer` | `SnowflakeSqlExpressionRenderer` |

## Method overrides

| Method | Snowflake divergence from default ANSI |
|---|---|
| `supportedPercentileFunctionTypes` | `CONTINUOUS`, `DISCRETE`, `APPROXIMATE_CONTINUOUS` |
| `renderDatePart` | `DOW` → `dayofweekiso` |
| `visitGenerateUuidExpr` | `UUID_STRING()` |
| `visitPercentileExpr` | `PERCENTILE_CONT` / `PERCENTILE_DISC` / `APPROX_PERCENTILE` |

## Notable quirks

- Snowflake uses `WITHIN GROUP (ORDER BY (...))` percentile syntax — same as Postgres,
  Redshift, DuckDB.
- `APPROXIMATE_DISCRETE` is rejected (Snowflake doesn't expose it as a SQL function).
- `dayofweekiso` keeps DOW ISO-compliant without any post-processing (cf. BigQuery /
  Redshift which need `IF(...)` / `CASE WHEN` shims).

## Wave

W6.
