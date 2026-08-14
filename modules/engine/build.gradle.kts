plugins {
    id("metricflow.kotlin-module")
}

// This artifact is deliberately transport-free. Consumers select the dialect
// renderer modules they need and register them through SqlPlanRendererRegistry.
dependencies {
    implementation(project(":core"))
    testImplementation(project(":render-bigquery"))
}

tasks.named<Test>("test") {
    systemProperty("metricflow.repoRoot", rootProject.projectDir.absolutePath)
}

val forbiddenLibraryRuntimeArtifacts = setOf(
    "grpc-api",
    "grpc-core",
    "grpc-netty-shaded",
    "grpc-protobuf",
    "grpc-stub",
    "grpc-kotlin-stub",
    "logback-classic",
    "metricflow-render-bigquery",
    "metricflow-render-databricks",
    "metricflow-render-duckdb",
    "metricflow-render-postgres",
    "metricflow-render-redshift",
    "metricflow-render-snowflake",
    "metricflow-render-trino",
)

tasks.register("verifyLibraryRuntimeClasspath") {
    group = "verification"
    description = "Ensures the in-process engine does not transitively include transport or dialect runtimes."
    doLast {
        val resolvedArtifacts = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        val forbidden = resolvedArtifacts
            .map { it.moduleVersion.id.name }
            .filter { it in forbiddenLibraryRuntimeArtifacts }
            .sorted()
        check(forbidden.isEmpty()) {
            "metricflow-engine runtime classpath contains forbidden artifacts: ${forbidden.joinToString(", ")}"
        }
    }
}
