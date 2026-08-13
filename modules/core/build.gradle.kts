plugins {
    id("metricflow.kotlin-module")
}

// `:core` is the metricflow-core artifact — the result of the Phase 5 consolidation that
// absorbed the 17 fine-grained `:common:*` and `:domain:*` Gradle modules from Phase 3.
//
// Kotlin packages are preserved verbatim from the source modules (see PHASE_5_PLAN.md
// section 3) so that Python source-path traceability remains intact and `import` lines
// in dialect renderers / engine facade / diff-runner do not need to be rewritten.
//
// External dependencies are entirely transitive through the `metricflow.kotlin-module`
// convention plugin (kotlin-stdlib, kotlinx-serialization, slf4j) — `:core` does not pull
// in any heavy runtime such as gRPC or protobuf. Those belong to `:engine` (the facade +
// gRPC server) only.

dependencies {
    // No project deps — `:core` is the bottom of the dependency graph for the library.
}

// Several of the absorbed test suites (manifest model round-trip, lookup parity,
// validation parity, query, semantic-graph, etc.) read corpus fixtures via a system
// property pointing at the repo root. We surface it once at the merged module level.
tasks.named<Test>("test") {
    systemProperty("metricflow.repoRoot", rootProject.projectDir.absolutePath)
    // The transformer parity test additionally needs the Python oracle venv interpreter.
    systemProperty(
        "metricflow.pythonInterpreter",
        rootProject.projectDir.resolve("python_oracle/.venv/bin/python").absolutePath,
    )
}
