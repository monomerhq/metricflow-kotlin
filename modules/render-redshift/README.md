# `:infrastructure:sql:render:redshift`

SQL renderer for the Redshift engine.

## Python sources mapped

| Python | Kotlin |
|---|---|
| `metricflow.sql.render.redshift.RedshiftSqlPlanRenderer` | `RedshiftSqlPlanRenderer` |
| `metricflow.sql.render.redshift.RedshiftSqlExpressionRenderer` | `RedshiftSqlExpressionRenderer` |

## Method overrides

| Method | Redshift divergence from default ANSI |
|---|---|
| `doubleDataType` | `DOUBLE PRECISION` |
| `supportedPercentileFunctionTypes` | `CONTINUOUS`, `APPROXIMATE_DISCRETE` |
| `visitPercentileExpr` | `PERCENTILE_CONT` / `APPROXIMATE PERCENTILE_DISC` |
| `renderDatePart` | identity — `DOW` is rendered as `dow`, not the ANSI base `isodow` |
| `visitExtractExpr` | post-processes `DOW` to renormalise 0..6 → ISO 1..7 |
| `visitGenerateUuidExpr` | RANDOM-concat hack — Redshift has no native UUID function |

## Notable quirks

- Redshift's `EXTRACT(DOW FROM ...)` returns 0..6 (Sunday=0); we shift Sunday → 7 via
  a `CASE WHEN ... THEN ... + 7 ELSE ... END` so output is ISO 1..7 (Monday..Sunday).
- `APPROXIMATE PERCENTILE_DISC` is Redshift-specific syntax (two words, not an
  identifier).
- The UUID hack is documented in Python as temporary — Redshift has no UUID generation
  function, so we concatenate two random integers cast to varchar.

## Wave

W6.
