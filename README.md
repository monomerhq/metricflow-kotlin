# metricflow-kotlin

A Kotlin/JVM port of [dbt MetricFlow](https://github.com/dbt-labs/metricflow)'s
semantic-layer engine. **Generates** the dialect-specific SQL for metric queries;
does NOT execute it. Bring your own warehouse client.

Tracks `metricflow` 0.210.0 (2026-04-28, Apache-2.0). The port keeps upstream
attribution in [`NOTICE`](NOTICE), and [`UPSTREAM.md`](UPSTREAM.md) records the
exact source revision and vendoring boundary.

---

## Quickstart

```kotlin
dependencies {
    implementation("cc.monomer.metricflow:metricflow-core:VERSION")
    implementation("cc.monomer.metricflow:metricflow-render-trino:VERSION")
    // pick whichever dialect(s) your warehouse needs
}
```

```kotlin
import cc.monomer.metricflow.application.engine.MetricFlowEngine
import cc.monomer.metricflow.application.engine.MetricFlowExplainRequest
import cc.monomer.metricflow.domain.sql.render.SqlEngine

val engine = MetricFlowEngine(semanticManifest)

// What dimensions / metrics does this manifest expose?
val metrics = engine.listMetrics(includeDimensions = true)

// Validate the manifest against MetricFlow's 28-rule semantic check
val results = engine.validateManifest()

// Compile a metric query to SQL — no execution
val explained = engine.explain(
    MetricFlowExplainRequest(
        metricNames = listOf("bookings"),
        groupByNames = listOf("listing__country", "metric_time__day"),
        whereConstraints = null,
        orderByNames = null,
        limit = 100,
        timeConstraintStart = "2020-01-01",
        timeConstraintEnd = "2020-02-01",
        savedQueryName = null,
        minMaxOnly = false,
        applyGroupBy = true,
        orderOutputColumnsByInputOrder = false,
        dialect = SqlEngine.TRINO,
    ),
)
println(explained.sql)  // ← the rendered Trino SQL
```

The full public API surface is documented in [`docs/PUBLIC_API.md`](docs/PUBLIC_API.md).

The published namespace is `cc.monomer.metricflow`; there is no compatibility
artifact for the former private package.

The artifacts are not yet published to a public Maven repository. Until the
first release, `VERSION` coordinates are available only from the local staging
repository produced by the verification command below.

---

## Modules

Phase 5 (May 2026) consolidated the 33 Phase-3 modules down to 10 — **9 publishable
artifacts + 1 internal-only**:

| Module | Maven coordinate | Description |
|---|---|---|
| `core` | `metricflow-core` | Manifest model + validation + specs + dataflow plan + SQL plan + default ANSI renderer + engine facade. **The library.** |
| `render-trino` | `metricflow-render-trino` | Trino dialect renderer |
| `render-bigquery` | `metricflow-render-bigquery` | BigQuery dialect renderer |
| `render-snowflake` | `metricflow-render-snowflake` | Snowflake dialect renderer |
| `render-databricks` | `metricflow-render-databricks` | Databricks dialect renderer |
| `render-redshift` | `metricflow-render-redshift` | Redshift dialect renderer |
| `render-duckdb` | `metricflow-render-duckdb` | DuckDB dialect renderer |
| `render-postgres` | `metricflow-render-postgres` | Postgres dialect renderer |
| `engine` | `metricflow-engine` | Engine facade + optional gRPC server. **Phase-5 note:** the gRPC pieces have not yet been split into a separate `:grpc-server` artifact — the engine facade (`MetricFlowEngine`) currently lives here alongside the server bootstrap. Consumers using the library-only mode should ignore the gRPC classes. |
| `internal-tests/diff-runner` | (not published) | Differential test runner that compares Kotlin output against the Python oracle for the entire 112-case corpus |

`:core` carries no gRPC/Netty/protobuf dependencies; it is pure Kotlin + kotlinx-serialization.

---

## What this library does NOT do

- **Execute SQL.** MetricFlow's responsibility ends at SQL generation. Per the
  project mission (`CLAUDE.md`), the Python `MetricFlowEngine.query`,
  `metricflow.execution/`, and `SqlClient` execution methods are deliberately
  not ported. Bring your own JDBC / warehouse client.
- **Re-implement `dbt`.** Manifest parsing from `dbt_project.yml` / `metrics.yml`
  is upstream; consumers supply a `SemanticManifest` (typically deserialised
  from the Python oracle's canonical JSON output).

---

## Running the corpus diff-runner

The internal correctness gate. Compares Kotlin against the Python oracle on the
112-case corpus:

```bash
./gradlew :internal-diff-runner:run
```

A green run prints `PASS=112 FAIL=0 UNIMPLEMENTED=0 ERROR=0`.

## Verify the Maven artifacts

```bash
./gradlew verifyPublicRepository
```

This runs every public module check, verifies the 112-case differential corpus,
and publishes POM, binary, sources, and javadoc JARs into
`build/maven-staging/`. It does not contact a remote repository.

---

## Contributor documentation

- [`CONTRIBUTING.md`](CONTRIBUTING.md) — development and verification workflow
- [`docs/PUBLIC_API.md`](docs/PUBLIC_API.md) — consumer-facing API surface
- [`docs/guides/backend-conventions.md`](docs/guides/backend-conventions.md) — Kotlin coding conventions
- [`UPSTREAM.md`](UPSTREAM.md) — upstream source provenance and refresh policy

---

## License

Licensed under Apache-2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
