# `:infrastructure:sql:render:bigquery`

SQL renderer for the BigQuery engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.big_query.BigQuerySqlPlanRenderer` | `BigQuerySqlPlanRenderer` |
| `metricflow.sql.render.big_query.BigQuerySqlExpressionRenderer` | `BigQuerySqlExpressionRenderer` |

## Method overrides

| Method | BigQuery divergence from default ANSI |
|---|---|
| `doubleDataType` | `FLOAT64` |
| `timestampDataType` | `DATETIME` (time-zone-agnostic; see Python docstring) |
| `supportedPercentileFunctionTypes` | only `APPROXIMATE_CONTINUOUS` |
| `renderGroupByExpr` | references SELECT alias instead of repeating the expression |
| `visitPercentileExpr` | `APPROX_QUANTILES(arg, denominator)[OFFSET(numerator)]` |
| `visitCastToTimestampExpr` | cast to `DATETIME` |
| `visitDateTruncExpr` | `DATETIME_TRUNC(arg, gran)` — opposite arg order from Snowflake/Redshift; ISO prefix for `WEEK` |
| `renderDatePart` | `DOY` → `dayofyear`, `DOW` → `dayofweek` |
| `visitExtractExpr` | post-processes `DOW` to renormalise to ISO 1..7 |
| `visitSubtractTimeIntervalExpr` | `DATE_SUB(CAST(arg AS DATETIME), INTERVAL count gran)` |
| `visitAddTimeExpr` | `DATE_ADD(CAST(arg AS DATETIME), INTERVAL count gran)` |
| `visitGenerateUuidExpr` | `GENERATE_UUID()` |

## Notable quirks

- **Percentile rendering uses a continued-fraction reduction** of the percentile value
  (matching Python's `fractions.Fraction(p).limit_denominator()`). 0.5 becomes
  `OFFSET(1)` against a 2-quantile pool, 0.1 becomes `OFFSET(1)` against a 10-quantile
  pool, etc. See [`BigQuerySqlExpressionRenderer.doubleToLimitedFraction`](src/main/kotlin/cc/monomer/metricflow/infrastructure/sql/render/bigquery/BigQuerySqlExpressionRenderer.kt).
- **DATETIME_TRUNC argument order is reversed from ANSI** (`(arg, gran)` instead of
  `(gran, arg)`). BigQuery is the outlier; Snowflake/Redshift keep ANSI ordering.
- **ISO-week prefix for `WEEK` granularity**: BigQuery defaults `WEEK` to Sunday-start,
  so we explicitly use `isoweek`.
- **DOW renormalisation**: BigQuery returns 1 (Sunday)..7 (Saturday); we shift to ISO
  1 (Monday)..7 (Sunday) with an inline `IF(...)`.

## Wave

W6.
