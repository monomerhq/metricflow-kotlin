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
| `visitDateTruncExpr` | `WEEK` → `DATEADD(day, 1 - DAYOFWEEKISO(x), DATE_TRUNC('day', x))` |
| `renderDatePart` | `DOW` → `dayofweekiso` |
| `visitGenerateUuidExpr` | `UUID_STRING()` |
| `visitPercentileExpr` | `PERCENTILE_CONT` / `PERCENTILE_DISC` / `APPROX_PERCENTILE` |

## Notable quirks

- Snowflake uses `WITHIN GROUP (ORDER BY (...))` percentile syntax — same as Postgres,
  Redshift, DuckDB.
- `APPROXIMATE_DISCRETE` is rejected (Snowflake doesn't expose it as a SQL function).
- `dayofweekiso` keeps DOW ISO-compliant without any post-processing (cf. BigQuery /
  Redshift which need `IF(...)` / `CASE WHEN` shims).
- Week truncation diverges from upstream `DATE_TRUNC('week', x)`, whose first weekday follows
  the account/session `WEEK_START` parameter. The rendered Monday uses `DAYOFWEEKISO`, which
  ignores `WEEK_START` and `WEEK_OF_YEAR_POLICY`, and keeps the input type (DATE stays DATE,
  a timestamp stays a timestamp). Other granularities, `DATEADD(week, n, x)` offsets, and
  `EXTRACT` date parts do not depend on these parameters and are unchanged.

## Wave

W6.
