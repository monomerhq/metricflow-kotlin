import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * Shared Kotlin conventions for every metricflow-kotlin module.
 *
 * - Kotlin 2.3 / JVM 25 toolchain (Homebrew openjdk@25.0.2 is the dev target).
 * - kotlinx-serialization applied everywhere; domain types use @Serializable.
 * - JUnit 5 + kotlin-test for unit tests.
 *
 * Dependency coordinates are inlined here (rather than pulled from the version
 * catalog) because precompiled-script-plugins can't see the catalog accessor
 * generated for module build.gradle.kts files. The single source of truth is
 * still gradle/libs.versions.toml — these constants below must mirror it.
 */

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

object MetricFlowDeps {
    const val KOTLIN = "2.3.20"
    const val KOTLINX_SERIALIZATION_JSON = "org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0"
    const val SLF4J_API = "org.slf4j:slf4j-api:2.0.16"
    const val LOGBACK_CLASSIC = "ch.qos.logback:logback-classic:1.5.12"
    const val JUNIT_API = "org.junit.jupiter:junit-jupiter-api:5.11.3"
    const val JUNIT_ENGINE = "org.junit.jupiter:junit-jupiter-engine:5.11.3"
    const val JUNIT_PLATFORM_LAUNCHER = "org.junit.platform:junit-platform-launcher:1.11.3"
    const val KOTLIN_TEST = "org.jetbrains.kotlin:kotlin-test:2.3.20"
    const val KOTLIN_TEST_JUNIT5 = "org.jetbrains.kotlin:kotlin-test-junit5:2.3.20"
}

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
}

dependencies {
    "implementation"("org.jetbrains.kotlin:kotlin-stdlib:${MetricFlowDeps.KOTLIN}")
    "implementation"(MetricFlowDeps.KOTLINX_SERIALIZATION_JSON)
    "implementation"(MetricFlowDeps.SLF4J_API)

    "testImplementation"(MetricFlowDeps.JUNIT_API)
    "testImplementation"(MetricFlowDeps.JUNIT_ENGINE)
    "testImplementation"(MetricFlowDeps.KOTLIN_TEST)
    "testImplementation"(MetricFlowDeps.KOTLIN_TEST_JUNIT5)
    "testRuntimeOnly"(MetricFlowDeps.JUNIT_PLATFORM_LAUNCHER)
    "testRuntimeOnly"(MetricFlowDeps.LOGBACK_CLASSIC)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_3)
        languageVersion.set(KotlinVersion.KOTLIN_2_3)
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) {
        into("META-INF")
    }
    from(rootProject.file("NOTICE")) {
        into("META-INF")
    }
}

if (project.path != ":internal-diff-runner") {
    pluginManager.apply("maven-publish")

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        withJavadocJar()
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = when (project.name) {
                    "core" -> "metricflow-core"
                    "engine" -> "metricflow-engine"
                    else -> "metricflow-${project.name}"
                }
                pom {
                    name.set("Monomer ${artifactId}")
                    description.set("A Kotlin port of MetricFlow's semantic query planning and SQL rendering engine.")
                    url.set("https://monomer.cc")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            organization.set("Monomer")
                            organizationUrl.set("https://monomer.cc")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/monomerhq/metricflow-kotlin.git")
                        developerConnection.set("scm:git:ssh://git@github.com/monomerhq/metricflow-kotlin.git")
                        url.set("https://github.com/monomerhq/metricflow-kotlin")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "MonomerStaging"
                url = rootProject.layout.buildDirectory.dir("maven-staging").get().asFile.toURI()
            }
        }
    }
}
