import com.google.protobuf.gradle.id

plugins {
    id("metricflow.kotlin-module")
    application
    id("com.google.protobuf")
}

sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/protos")
        }
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":core"))
    implementation(libs.bundles.grpc.runtime)
    implementation(libs.bundles.grpc.server)
    implementation(libs.grpc.inprocess)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)
    implementation(libs.logback.classic)

    // The optional server is the composition root for the public dialect set.
    // Library consumers can omit this artifact and register only their chosen
    // renderer modules against metricflow-engine.
    implementation(project(":render-trino"))
    implementation(project(":render-bigquery"))
    implementation(project(":render-snowflake"))
    implementation(project(":render-databricks"))
    implementation(project(":render-redshift"))
    implementation(project(":render-duckdb"))
    implementation(project(":render-postgres"))
}

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

application {
    mainClass.set("cc.monomer.metricflow.application.engine.MetricFlowGrpcServerKt")
}

tasks.named<Test>("test") {
    systemProperty("metricflow.repoRoot", rootProject.projectDir.absolutePath)
}
