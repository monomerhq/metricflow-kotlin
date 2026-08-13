/*
 * Root build script. The actual Kotlin / serialization / proto plugins are
 * applied per-module via the `metricflow.kotlin-module` precompiled
 * convention plugin (see `buildSrc/src/main/kotlin/`).
 */

plugins {
    id("org.cyclonedx.bom") version "3.3.0"
}

group = "cc.monomer.metricflow"
version = "0.1.0"

allprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.register("verifyPublicArtifact") {
    group = "verification"
    description = "Builds tests, the public SBOM, and every public Maven publication into the local staging repository."
    dependsOn(
        "cyclonedxBom",
        ":core:check",
        ":engine:check",
        ":render-trino:check",
        ":render-bigquery:check",
        ":render-snowflake:check",
        ":render-databricks:check",
        ":render-redshift:check",
        ":render-duckdb:check",
        ":render-postgres:check",
        ":core:publishAllPublicationsToMonomerStagingRepository",
        ":engine:publishAllPublicationsToMonomerStagingRepository",
        ":render-trino:publishAllPublicationsToMonomerStagingRepository",
        ":render-bigquery:publishAllPublicationsToMonomerStagingRepository",
        ":render-snowflake:publishAllPublicationsToMonomerStagingRepository",
        ":render-databricks:publishAllPublicationsToMonomerStagingRepository",
        ":render-redshift:publishAllPublicationsToMonomerStagingRepository",
        ":render-duckdb:publishAllPublicationsToMonomerStagingRepository",
        ":render-postgres:publishAllPublicationsToMonomerStagingRepository",
    )
}

tasks.register("verifyPublicRepository") {
    group = "verification"
    description = "Runs the public artifact and differential-corpus release gates."
    dependsOn("verifyPublicArtifact", ":internal-diff-runner:run")
}

tasks.cyclonedxBom {
    componentGroup = project.group.toString()
    componentName = rootProject.name
    componentVersion = project.version.toString()
    includeBomSerialNumber = false
    includeBuildSystem = false
}

project(":internal-diff-runner") {
    tasks.cyclonedxDirectBom {
        enabled = false
    }
}
