plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "metricflow-kotlin"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

/**
 * Declare a module and immediately remap its directory under `modules/`.
 * Using a function keeps each entry one line and prevents the issue where
 * Gradle 9 inspects `project.projectDir` *before* a deferred `forEach { … }`
 * remap runs.
 */
fun module(path: String) {
    include(path)
    val diskPath = path.removePrefix(":").replace(':', '/')
    project(path).projectDir = file("modules/$diskPath")

    // When a path like `:domain:manifest:model` is included, Gradle implicitly
    // creates intermediate parents (`:domain`, `:domain:manifest`). Re-map
    // their projectDir too so they live under `modules/` and not the repo root.
    val segments = path.removePrefix(":").split(":")
    for (i in 1 until segments.size) {
        val parentPath = ":" + segments.subList(0, i).joinToString(":")
        val parentDiskPath = segments.subList(0, i).joinToString("/")
        project(parentPath).projectDir = file("modules/$parentDiskPath")
    }
}

// Core — Phase-5 consolidation of every :common:* and :domain:* Phase-3 module.
// Single metricflow-core JAR; Kotlin packages preserved verbatim.
module(":core")

// Dialect renderers — one JAR per SQL engine. Consumers pick only what they need.
module(":render-trino")
module(":render-bigquery")
module(":render-snowflake")
module(":render-databricks")
module(":render-redshift")
module(":render-duckdb")
module(":render-postgres")

// In-process engine facade. It depends only on :core and receives dialect renderers
// through an explicit `SqlPlanRendererRegistry` supplied by the consumer.
module(":engine")

// Optional transport capability. This module owns protobuf/gRPC code generation,
// the server bootstrap, and the wire adapters; library consumers do not need it.
module(":grpc-server")

// Internal-only — not publishable. The flat Gradle path avoids publishing or
// aggregating the intermediate source-layout directory as a component.
include(":internal-diff-runner")
project(":internal-diff-runner").projectDir = file("modules/internal-tests/diff-runner")
