plugins {
    id("metricflow.kotlin-module")
    application
}

// Internal-only — this module is NOT published. It runs the corpus diff-runner against
// the Python oracle outputs and is the project's safety net.
plugins.withId("maven-publish") {
    tasks.matching { it.name.startsWith("publish") }.configureEach { enabled = false }
}

dependencies {
    implementation(project(":engine"))
    // Diff-runner invokes the engine directly (in-process) so it can compare
    // canonical JSON, not just proto fields. `:core` exposes the manifest
    // model + transformer + validator + SqlEngine enum used during hydration
    // and dialect dispatch.
    implementation(project(":core"))
    implementation(project(":render-trino"))
    implementation(project(":render-bigquery"))
    implementation(project(":render-snowflake"))
    implementation(project(":render-databricks"))
    implementation(project(":render-redshift"))
    implementation(project(":render-duckdb"))
    implementation(project(":render-postgres"))
    implementation(libs.bundles.grpc.runtime)
    implementation(libs.grpc.inprocess)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.logback.classic)
}

application {
    mainClass.set("cc.monomer.metricflow.integration.diff.DiffRunnerKt")
}

// Diff-runner reads corpus/<case>/... from the repository root. JavaExec
// (the runtime used by the `run` task) defaults to module dir; we override.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
