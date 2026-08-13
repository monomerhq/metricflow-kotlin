---
summary: "Kotlin + Spring backend conventions shared across all backend services."
read_when:
  - Implementing any backend feature
  - Writing new service code, handlers, repositories, or tests
status: stable
---

# Backend Conventions

All Kotlin + Spring backend services: dashboard-service, query-service, catalog-service, auth-service.

## Architecture

Hexagonal (ports-and-adapters). Domain and application layers have zero infrastructure dependency.

```
domain/          ← Pure domain models, port interfaces
application/     ← UseCases, TransactionRunner
inbound/         ← REST handlers, WebSocket, CoWebFilter
outbound/        ← Adapters (MySQL, PostgreSQL, gRPC, S3)
```

Separate QueryPort (read, `suspend fun`) from Repository (write, blocking `fun`). UseCase depends only on port interfaces.

### Naming

Outbound adapter classes: **tech prefix + role suffix**. No `-Adapter` or `-Impl`.

```kotlin
class MysqlDashboardRepository       // tech prefix + role
class PostgresSearchProjector        // tech prefix + role
class MonomerSqlQueryExecutor           // tech prefix + role
```

Port interfaces may use `-Port` suffix: `DashboardQueryPort`, `SearchProjectionPort`.

## Reactive + Coroutines

WebFlux with Kotlin coroutines natively. All handlers, filters, UseCases are `suspend fun`. Do not use `Mono`/`Flux` directly.

## Transactions

```
UseCase
  └─ transactionRunner.run { }          ← write transaction (VT)
       └─ repository.insert/update()    ← blocking JDBC

QueryPort adapter
  └─ transactionRunner.readOnly { }     ← read-only transaction (VT)
       └─ jdbc.query()                  ← blocking JDBC
```

- Write transactions: UseCase calls `transactionRunner.run { }` explicitly.
- Read-only: QueryPort adapter wraps with `transactionRunner.readOnly { }`.
- Repository methods are blocking `fun` (not `suspend`). Called inside `run { }`.
- QueryPort methods are `suspend fun`. Called outside `run { }`.
- `Dispatchers.VT` is handled inside `SpringTransactionRunner`. No manual dispatch needed.
- Do NOT add `spring.threads.virtual.enabled` to WebFlux/Netty services.

## Domain Models

### IDs: UUID v7 + value class

`kotlin.uuid.Uuid` (stdlib, Kotlin 2.2+ stable). No interface, no abstract factory.

```kotlin
@JvmInline
@Serializable
value class DashboardId(val value: Uuid) {
    companion object {
        fun generate(): DashboardId = DashboardId(Uuid.generateV7())
    }

    override fun toString(): String = value.toString()
}
```

**API boundary (Controller):** `DashboardId(Uuid.parse(pathVariable))`

**DB boundary (Repository):**
- PostgreSQL column type: `UUID` (native, 16 bytes)
- Write: `id.value.toJavaUuid()` in parameter map
- Read: `DashboardId(rs.getUuid("id"))` via `ResultSet.getUuid()` extension

```kotlin
// outbound/jdbc/JdbcSupport.kt
fun ResultSet.getUuid(columnLabel: String): Uuid =
    getObject(columnLabel, java.util.UUID::class.java).toKotlinUuid()
```

Use `@JvmInline value class` for any primitive that benefits from type safety (IDs, names, codes).

### Initialization: companion factory

Domain models own their creation. Use `companion object` factory, not external construction.

```kotlin
class Dashboard private constructor(
    val id: DashboardId,
    val title: String,
    val createdAt: Instant,
) {
    companion object {
        fun create(title: String): Dashboard = Dashboard(
            id = DashboardId.generate(),
            title = title,
            createdAt = Instant.now(),
        )
    }

    fun updateTitle(title: String): Dashboard = copy(title = title)
}
```

DB reconstruction uses a separate adapter-level mapper, not the `create()` factory.

### State transitions

Domain models own state changes via methods returning new instances (`copy()`). Adapters only map.

## Explicit Code

- No default parameter values. Callers pass all arguments explicitly.
- Exception: only when benefit is very high (e.g., pagination `page=0, size=20`).

## UseCase Pattern

Typed Command/Response nested inside UseCase interface.

```kotlin
interface DashboardUseCase {
    suspend fun create(command: CreateCommand): DraftResponse

    data class CreateCommand(val title: String, val userId: MonomerUserId)
    data class DraftResponse(val id: DashboardId, val title: String)
}
```

Shared Response types go to a common location in application layer.

## Tests

Tests are split by Gradle sourceSet — physical directory = category. Classpaths are separated so heavy dependencies (testcontainers, real SDK clients) can't leak into unit compile.

| SourceSet | Path | Purpose | Gradle task |
|-----------|------|---------|-------------|
| `test` | `src/test/` | Unit — in-process only, no external deps | `./gradlew test` |
| `integrationTest` | `src/integrationTest/` | Testcontainers / hermetic I/O | `./gradlew integrationTest` |
| `externalTest` | `src/externalTest/` | Real remote services (credentials required) | `./gradlew externalTest` |

Required on every service: `test` and `integrationTest`. `externalTest` is optional — create only when a service needs one-shot checks against a real remote.

`check` depends on `test` + `integrationTest` (so `./gradlew build` runs both; CI requires Docker). `externalTest` is **not** wired into `check` — invoke manually.

Template (drop into `build.gradle.kts`):

```kotlin
sourceSets {
    create("integrationTest") {
        kotlin.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
}
val integrationTestImplementation by configurations.getting { extendsFrom(configurations.testImplementation.get()) }
val integrationTestRuntimeOnly by configurations.getting { extendsFrom(configurations.testRuntimeOnly.get()) }

tasks.register<Test>("integrationTest") {
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter("test")
}
tasks.named("check") { dependsOn("integrationTest") }
```

Heavy deps (testcontainers, real-service SDKs) go in `integrationTestImplementation` — not `testImplementation` — so they stay out of the unit classpath.

## FP Style

Use practical FP. No FP libraries (Arrow etc.).

- Immutable `data class` + `copy()`
- Collection chaining (`map`, `filter`, `fold`)
- `sealed interface` for sum types
- Extension functions for adapter mapping

## Error Handling

Practical errors-as-values. UseCases and domain services return sealed results for business outcomes; throw only for system failures (DB/gRPC unavailable, programmer error) — the global handler maps to 5xx.

- `require` / `check` for domain invariants — broken means programmer bug.
- Value objects validate at construction (`fun of(raw): SealedOutcome`); domain entities accept already-validated value objects.
- `txRunner.run { }` is an atomic write boundary — decide outside, write inside. Concurrency-sensitive checks return a sealed `UpdateOutcome` from a conditional `UPDATE`. Multi-step rollback (rare): throw a private sentinel inside the block, catch the exact type outside.
- At trust boundaries (parsing, foreign SDKs, driver SQL exceptions), catch the specific exception type and convert to a sealed outcome.
- Sealed results map to HTTP at the handler with `when` (compile-time exhaustiveness).

## Serialization

`kotlinx-serialization` (not Jackson). `@Serializable` on data classes and value classes.
