/*
 * Root build script. The actual Kotlin / serialization / proto plugins are
 * applied per-module via the `metricflow.kotlin-module` precompiled
 * convention plugin (see `buildSrc/src/main/kotlin/`).
 */

import groovy.json.JsonOutput
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("org.cyclonedx.bom") version "3.3.0"
}

group = "cc.monomer.metricflow"
version = "0.2.0"

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
        ":engine:verifyLibraryRuntimeClasspath",
        ":grpc-server:check",
        ":render-trino:check",
        ":render-bigquery:check",
        ":render-snowflake:check",
        ":render-databricks:check",
        ":render-redshift:check",
        ":render-duckdb:check",
        ":render-postgres:check",
        ":core:publishAllPublicationsToMonomerStagingRepository",
        ":engine:publishAllPublicationsToMonomerStagingRepository",
        ":grpc-server:publishAllPublicationsToMonomerStagingRepository",
        ":render-trino:publishAllPublicationsToMonomerStagingRepository",
        ":render-bigquery:publishAllPublicationsToMonomerStagingRepository",
        ":render-snowflake:publishAllPublicationsToMonomerStagingRepository",
        ":render-databricks:publishAllPublicationsToMonomerStagingRepository",
        ":render-redshift:publishAllPublicationsToMonomerStagingRepository",
        ":render-duckdb:publishAllPublicationsToMonomerStagingRepository",
        ":render-postgres:publishAllPublicationsToMonomerStagingRepository",
    )
}

tasks.register("printVersion") {
    group = "help"
    description = "Prints the immutable release version used by Maven publications."
    doLast { println(project.version) }
}

tasks.register("verifyPublicRepository") {
    group = "verification"
    description = "Runs the public artifact and differential-corpus release gates."
    dependsOn("verifyPublicArtifact", ":internal-diff-runner:run", "verifyMonomerProductBundle")
}

val monomerProductArtifactIds = listOf(
    "metricflow-core",
    "metricflow-engine",
    "metricflow-render-bigquery",
    "metricflow-render-databricks",
    "metricflow-render-postgres",
    "metricflow-render-redshift",
    "metricflow-render-snowflake",
    "metricflow-render-trino",
)

fun sha256Hex(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

fun gitRevision(): String {
    val process = ProcessBuilder("git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0 && output.isNotEmpty()) { "Unable to resolve the release source revision" }
    return output
}

fun jsonText(value: Any): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"

val monomerProductBundleRoot = layout.buildDirectory.dir("monomer-product-bundle")

val prepareMonomerProductBundle = tasks.register("prepareMonomerProductBundle") {
    group = "distribution"
    description = "Assembles the exact Maven repository and evidence inputs shipped to Monomer."
    dependsOn("verifyPublicArtifact")

    doLast {
        val bundleRoot = monomerProductBundleRoot.get().asFile
        check(bundleRoot.absolutePath.startsWith(layout.buildDirectory.get().asFile.absolutePath)) {
            "Product bundle output must stay under the build directory"
        }
        bundleRoot.deleteRecursively()
        val repositoryRoot = File(bundleRoot, "repository")
        val evidenceRoot = File(bundleRoot, "evidence")
        val legalRoot = File(bundleRoot, "legal")
        repositoryRoot.mkdirs()
        evidenceRoot.mkdirs()
        legalRoot.mkdirs()

        val stagingRoot = layout.buildDirectory.dir("maven-staging").get().asFile
        val expectedGroupPath = "cc/monomer/metricflow"
        for (artifactId in monomerProductArtifactIds) {
            val source = File(stagingRoot, "$expectedGroupPath/$artifactId/${project.version}")
            check(source.isDirectory) {
                "Public staging is missing product artifact $artifactId version ${project.version}: ${source.absolutePath}"
            }
            source.copyRecursively(
                File(repositoryRoot, "$expectedGroupPath/$artifactId/${project.version}"),
                overwrite = true,
            )
        }

        rootProject.file("LICENSE").copyTo(File(legalRoot, "LICENSE"), overwrite = true)
        rootProject.file("NOTICE").copyTo(File(legalRoot, "NOTICE"), overwrite = true)

        val workspaceSbom = rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json").get().asFile
        check(workspaceSbom.isFile) { "CycloneDX SBOM was not generated at ${workspaceSbom.absolutePath}" }
        // CycloneDX's generated timestamp is useful for an interactive report but
        // would make an otherwise content-addressed product bundle change on every
        // build. The source revision in provenance-input.json is the stable build
        // identity for this release evidence.
        val deterministicSbom = workspaceSbom.readText(StandardCharsets.UTF_8)
            .replace(Regex("(?m)^\\s*\\\"timestamp\\\"\\s*:\\s*\\\"[^\\\"]+\\\",\\s*\\n"), "")
        File(evidenceRoot, "workspace-sbom.cyclonedx.json").writeText(
            deterministicSbom,
            StandardCharsets.UTF_8,
        )

        val dependencyEvidence = monomerProductArtifactIds.sorted().map { artifactId ->
            val artifactDirectory = File(repositoryRoot, "$expectedGroupPath/$artifactId")
            val versionDirectory = File(artifactDirectory, project.version.toString())
            val pom = File(versionDirectory, "$artifactId-${project.version}.pom")
            check(pom.isFile) { "Missing POM for $artifactId" }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
            val dependencies = (0 until document.getElementsByTagName("dependency").length).map { index ->
                val element = document.getElementsByTagName("dependency").item(index) as org.w3c.dom.Element
                fun value(name: String): String =
                    element.getElementsByTagName(name).item(0)?.textContent?.trim().orEmpty()
                mapOf(
                    "groupId" to value("groupId"),
                    "artifactId" to value("artifactId"),
                    "version" to value("version"),
                    "scope" to value("scope"),
                )
            }
            mapOf(
                "artifactId" to artifactId,
                "pom" to "$expectedGroupPath/$artifactId/${project.version}/${pom.name}",
                "sha256" to sha256Hex(pom),
                "dependencies" to dependencies,
            )
        }
        File(evidenceRoot, "dependency-evidence.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/dependency-evidence/v1",
                    "artifacts" to dependencyEvidence,
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(evidenceRoot, "licenses.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/license-evidence/v1",
                    "license" to "Apache-2.0",
                    "attributionFiles" to listOf("legal/LICENSE", "legal/NOTICE"),
                    "artifacts" to monomerProductArtifactIds.sorted(),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val sourceRevision = gitRevision()
        File(evidenceRoot, "provenance-input.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/provenance-input/v1",
                    "source" to mapOf(
                        "repository" to "https://github.com/monomerhq/metricflow-kotlin",
                        "revision" to sourceRevision,
                    ),
                    "build" to mapOf(
                        "command" to "./gradlew verifyPublicRepository --no-daemon",
                        "gradleProject" to rootProject.name,
                        "version" to project.version.toString(),
                    ),
                    "productArtifacts" to monomerProductArtifactIds.sorted(),
                    "attestation" to "GitHub Actions actions/attest-build-provenance adds the signed release attestation.",
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val bundleManifest = File(evidenceRoot, "bundle-manifest.json")
        bundleManifest.writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/maven-bundle/v1",
                    "groupId" to project.group.toString(),
                    "version" to project.version.toString(),
                    "repository" to "repository",
                    "artifacts" to monomerProductArtifactIds.sorted(),
                    "excludedArtifacts" to listOf("metricflow-render-duckdb", "metricflow-grpc-server"),
                    "sha256Manifest" to "SHA256SUMS",
                    "evidence" to listOf(
                        "evidence/workspace-sbom.cyclonedx.json",
                        "evidence/dependency-evidence.json",
                        "evidence/licenses.json",
                        "evidence/provenance-input.json",
                    ),
                    "sourceRevision" to sourceRevision,
                ),
            ),
            StandardCharsets.UTF_8,
        )

        val filesForManifest = Files.walk(bundleRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { bundleRoot.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
                .filter { it != "SHA256SUMS" }
                .sorted()
                .toList()
        }
        val checksums = filesForManifest.joinToString(separator = "\n", postfix = "\n") { relativePath ->
            "${sha256Hex(File(bundleRoot, relativePath))}  $relativePath"
        }
        File(bundleRoot, "SHA256SUMS").writeText(checksums, StandardCharsets.UTF_8)
    }
}

val packageMonomerProductBundle = tasks.register<Zip>("packageMonomerProductBundle") {
    group = "distribution"
    description = "Creates the deterministic Monomer Maven product bundle."
    dependsOn(prepareMonomerProductBundle)
    archiveBaseName.set("metricflow-monomer-product")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("bundles"))
    from(monomerProductBundleRoot)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register("verifyMonomerProductBundle") {
    group = "verification"
    description = "Validates the product bundle contents, checksums, and excluded capabilities."
    dependsOn(packageMonomerProductBundle)
    doLast {
        val archive = packageMonomerProductBundle.get().archiveFile.get().asFile
        check(archive.isFile) { "Product bundle was not created: ${archive.absolutePath}" }
        val verificationRoot = layout.buildDirectory.dir("monomer-product-bundle-verification").get().asFile
        verificationRoot.deleteRecursively()
        copy {
            from(zipTree(archive))
            into(verificationRoot)
        }
        val checksumsFile = File(verificationRoot, "SHA256SUMS")
        check(checksumsFile.isFile) { "Product bundle is missing SHA256SUMS" }
        val checksumEntries = checksumsFile.readLines(StandardCharsets.UTF_8)
            .filter { it.isNotBlank() }
            .map { line ->
                val separator = line.indexOf("  ")
                check(separator > 0) { "Malformed SHA256SUMS entry: $line" }
                line.substring(0, separator) to line.substring(separator + 2)
            }
        for ((expectedDigest, relativePath) in checksumEntries) {
            val target = File(verificationRoot, relativePath)
            check(target.isFile) { "SHA256SUMS references missing file $relativePath" }
            check(sha256Hex(target) == expectedDigest) { "Checksum mismatch for $relativePath" }
        }
        val expectedRepositoryArtifacts = monomerProductArtifactIds.map {
            "repository/cc/monomer/metricflow/$it"
        }.toSet()
        val actualRepositoryArtifacts = File(verificationRoot, "repository/cc/monomer/metricflow")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.relativeTo(verificationRoot).path.replace(File.separatorChar, '/') }
            ?.toSet()
            ?: emptySet()
        check(actualRepositoryArtifacts == expectedRepositoryArtifacts) {
            "Product repository artifacts differ. expected=$expectedRepositoryArtifacts actual=$actualRepositoryArtifacts"
        }
        val forbiddenPaths = Files.walk(verificationRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { it.toString().replace(File.separatorChar, '/') }
                .filter { it.contains("metricflow-render-duckdb") || it.contains("metricflow-grpc-server") }
                .toList()
        }
        check(forbiddenPaths.isEmpty()) { "Product bundle contains excluded artifacts: $forbiddenPaths" }
        File(archive.parentFile, "${archive.name}.sha256").writeText(
            "${sha256Hex(archive)}  ${archive.name}\n",
            StandardCharsets.UTF_8,
        )
    }
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
