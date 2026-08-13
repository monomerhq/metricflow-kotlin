import com.google.protobuf.gradle.id

plugins {
    id("metricflow.kotlin-module")
    application
    id("com.google.protobuf")
}

// --- Source sets: pull the .proto out of repo-root protos/ -------------------
sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/protos")
        }
    }
}

// --- Dependencies -----------------------------------------------------------
dependencies {
    // gRPC + protobuf runtime
    implementation(libs.bundles.grpc.runtime)
    implementation(libs.bundles.grpc.server)
    implementation(libs.grpc.inprocess)           // for the in-process channel used by diff-runner
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)

    // Logging
    implementation(libs.logback.classic)

    // Phase-5 consolidation: `:core` replaces every :common:* and :domain:* module.
    // Dialect renderers remain split so consumers can pick only what they need.
    implementation(project(":core"))
    implementation(project(":render-trino"))
    implementation(project(":render-bigquery"))
    implementation(project(":render-snowflake"))
    implementation(project(":render-databricks"))
    implementation(project(":render-redshift"))
    implementation(project(":render-duckdb"))
    implementation(project(":render-postgres"))
}

// --- Proto codegen ----------------------------------------------------------
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.29.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.69.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.3.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("kotlin")
            }
            task.plugins {
                id("grpc")
                id("grpckt")
            }
        }
    }
}

// --- Application plugin: server entry point ---------------------------------
application {
    mainClass.set("cc.monomer.metricflow.application.engine.MetricFlowGrpcServerKt")
}

tasks.named<Test>("test") {
    systemProperty("metricflow.repoRoot", rootProject.projectDir.absolutePath)
}
