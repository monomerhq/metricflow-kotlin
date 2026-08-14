/*
 * Root build script. The actual Kotlin / serialization / proto plugins are
 * applied per-module via the `metricflow.kotlin-module` precompiled
 * convention plugin (see `buildSrc/src/main/kotlin/`).
 */

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.TreeMap
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.api.tasks.bundling.Zip
import java.util.zip.ZipFile

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
val monomerProductExcludedModules = listOf("internal-diff-runner", "metricflow-render-duckdb")
val metricFlowSourceUri = "https://github.com/monomerhq/metricflow-kotlin"
val monomerProductBundleName = "metricflow-monomer-product-${project.version}"

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

fun sha256Hex(file: File): String = sha256Hex(file.readBytes())

fun sha256Digest(file: File): String = "sha256:${sha256Hex(file)}"

fun canonicalValue(value: Any?): Any? = when (value) {
    is Map<*, *> -> TreeMap<String, Any?>().apply {
        value.forEach { (key, nestedValue) ->
            require(key is String) { "Canonical JSON object keys must be strings" }
            put(key, canonicalValue(nestedValue))
        }
    }
    is Iterable<*> -> value.map(::canonicalValue)
    else -> value
}

fun canonicalJson(value: Any): String = JsonOutput.toJson(canonicalValue(value))

fun canonicalSha256(value: Any): String = "sha256:" +
    sha256Hex(canonicalJson(value).toByteArray(StandardCharsets.UTF_8))

fun gitRevision(): String {
    val process = ProcessBuilder("git", "-C", rootProject.projectDir.absolutePath, "rev-parse", "HEAD")
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0 && output.isNotEmpty()) { "Unable to resolve the release source revision" }
    return output
}

fun jsonText(value: Any): String = JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n"

val monomerProductRepositoryRoot = layout.buildDirectory.dir("monomer-product-bundle/maven-repository")
val monomerProductReleaseAssetsRoot = layout.buildDirectory.dir("release-assets")

data class ProductArtifact(
    val artifactId: String,
    val coordinate: String,
    val relativePath: String,
    val sha256: String,
)

val prepareMonomerProductBundle = tasks.register("prepareMonomerProductBundle") {
    group = "distribution"
    description = "Assembles the exact Maven repository and separate release evidence inputs shipped to Monomer."
    dependsOn("verifyPublicArtifact")

    doLast {
        val buildRoot = layout.buildDirectory.get().asFile
        val repositoryRoot = monomerProductRepositoryRoot.get().asFile
        val releaseAssetsRoot = monomerProductReleaseAssetsRoot.get().asFile
        listOf(repositoryRoot, releaseAssetsRoot).forEach { outputRoot ->
            check(outputRoot.absolutePath.startsWith(buildRoot.absolutePath)) {
                "Product release output must stay under the build directory"
            }
            outputRoot.deleteRecursively()
        }
        repositoryRoot.mkdirs()

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

        val sourceRevision = gitRevision()
        val productArtifacts = monomerProductArtifactIds.sorted().map { artifactId ->
            val versionDirectory = File(repositoryRoot, "$expectedGroupPath/$artifactId/${project.version}")
            val jar = File(versionDirectory, "$artifactId-${project.version}.jar")
            check(jar.isFile) { "Missing primary JAR for $artifactId" }
            ProductArtifact(
                artifactId = artifactId,
                coordinate = "${project.group}:$artifactId:${project.version}",
                relativePath = "$expectedGroupPath/$artifactId/${project.version}/${jar.name}",
                sha256 = sha256Digest(jar),
            )
        }
        val markerArtifacts = productArtifacts.map { artifact ->
            mapOf(
                "coordinate" to artifact.coordinate,
                "relativePath" to artifact.relativePath,
                "sha256" to artifact.sha256,
            )
        }
        val artifactSetIdentity = mapOf(
            "schemaVersion" to 1,
            "version" to project.version.toString(),
            "source" to mapOf(
                "uri" to metricFlowSourceUri,
                "commit" to sourceRevision,
            ),
            "artifacts" to productArtifacts.map { artifact ->
                mapOf(
                    "coordinate" to artifact.coordinate,
                    "sha256" to artifact.sha256,
                )
            },
        )
        val artifactSetDigest = canonicalSha256(artifactSetIdentity)
        val marker = mapOf(
            "kind" to "cc.monomer.metricflow.maven-repository",
            "schemaVersion" to 1,
            "version" to project.version.toString(),
            "artifactSetDigest" to artifactSetDigest,
            "source" to mapOf(
                "uri" to metricFlowSourceUri,
                "commit" to sourceRevision,
            ),
            "repositoryRoot" to "maven-repository",
            "productModuleAllowlist" to monomerProductArtifactIds.sorted(),
            "excludedModules" to monomerProductExcludedModules,
            "artifacts" to markerArtifacts,
        )
        val markerFile = File(repositoryRoot, ".monomer-metricflow-manifest.json")
        markerFile.writeText(jsonText(marker), StandardCharsets.UTF_8)
        val bundleManifestAsset = File(releaseAssetsRoot, "$monomerProductBundleName.manifest.json")
        bundleManifestAsset.parentFile.mkdirs()
        bundleManifestAsset.writeText(jsonText(marker), StandardCharsets.UTF_8)

        val workspaceSbom = rootProject.layout.buildDirectory.file("reports/cyclonedx/bom.json").get().asFile
        check(workspaceSbom.isFile) { "CycloneDX SBOM was not generated at ${workspaceSbom.absolutePath}" }
        // CycloneDX's generated timestamp is useful for an interactive report but
        // would make an otherwise content-addressed product bundle change on every
        // build. The source revision in provenance-input.json is the stable build
        // identity for this release evidence.
        val deterministicSbom = workspaceSbom.readText(StandardCharsets.UTF_8)
            .replace(Regex("(?m)^\\s*\\\"timestamp\\\"\\s*:\\s*\\\"[^\\\"]+\\\",\\s*\\n"), "")
        File(releaseAssetsRoot, "$monomerProductBundleName.sbom.cyclonedx.json").writeText(
            deterministicSbom,
            StandardCharsets.UTF_8,
        )

        val dependencyEvidence = productArtifacts.map { artifact ->
            val versionDirectory = File(repositoryRoot, artifact.relativePath).parentFile
            val pom = File(versionDirectory, "${artifact.artifactId}-${project.version}.pom")
            check(pom.isFile) { "Missing POM for ${artifact.artifactId}" }
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
                "artifactId" to artifact.artifactId,
                "coordinate" to artifact.coordinate,
                "pom" to "maven-repository/${artifact.relativePath.substringBeforeLast("/")}/${pom.name}",
                "sha256" to sha256Hex(pom),
                "dependencies" to dependencies,
            )
        }
        File(releaseAssetsRoot, "$monomerProductBundleName.dependency-evidence.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/dependency-evidence/v1",
                    "artifacts" to dependencyEvidence,
                ),
            ),
            StandardCharsets.UTF_8,
        )
        File(releaseAssetsRoot, "$monomerProductBundleName.license-evidence.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/license-evidence/v1",
                    "license" to "Apache-2.0",
                    "attributionFiles" to listOf(
                        mapOf("path" to "LICENSE", "sha256" to sha256Digest(rootProject.file("LICENSE"))),
                        mapOf("path" to "NOTICE", "sha256" to sha256Digest(rootProject.file("NOTICE"))),
                    ),
                    "artifacts" to monomerProductArtifactIds.sorted(),
                ),
            ),
            StandardCharsets.UTF_8,
        )

        File(releaseAssetsRoot, "$monomerProductBundleName.provenance-input.json").writeText(
            jsonText(
                mapOf(
                    "schema" to "monomer.metricflow/provenance-input/v1",
                    "source" to mapOf(
                        "repository" to metricFlowSourceUri,
                        "revision" to sourceRevision,
                    ),
                    "build" to mapOf(
                        "command" to "./gradlew verifyPublicRepository --no-daemon",
                        "gradleProject" to rootProject.name,
                        "version" to project.version.toString(),
                    ),
                    "artifactSetDigest" to artifactSetDigest,
                    "productArtifacts" to productArtifacts.map { artifact -> artifact.coordinate },
                    "releaseEvidence" to mapOf(
                        "attestationAction" to "actions/attest@508db95dd578ae2727ebd6217d5ba78e4fbda05d",
                        "attestationStepId" to "attest",
                        "attestationUrlOutput" to "steps.attest.outputs.attestation-url",
                        "attestationBundleOutput" to "steps.attest.outputs.bundle-path",
                        "attestationAsset" to "$monomerProductBundleName.attestation.json",
                        "attestationReferenceAsset" to "$monomerProductBundleName.attestation-reference.json",
                    ),
                ),
            ),
            StandardCharsets.UTF_8,
        )
    }
}

val packageMonomerProductBundle = tasks.register<Zip>("packageMonomerProductBundle") {
    group = "distribution"
    description = "Creates the deterministic Monomer Maven product bundle."
    dependsOn(prepareMonomerProductBundle)
    archiveBaseName.set("metricflow-monomer-product")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("bundles"))
    from(monomerProductRepositoryRoot) {
        into("maven-repository")
    }
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
        val archiveEntries = ZipFile(archive).use { zipFile ->
            zipFile.entries().asSequence().map { entry -> entry.name }.toList()
        }
        check(archiveEntries.isNotEmpty()) { "Product bundle is empty" }
        check(archiveEntries.all { entry -> entry == "maven-repository/" || entry.startsWith("maven-repository/") }) {
            "Product bundle may contain only maven-repository/: $archiveEntries"
        }
        check("maven-repository/.monomer-metricflow-manifest.json" in archiveEntries) {
            "Product bundle is missing maven-repository/.monomer-metricflow-manifest.json"
        }
        val verificationRoot = layout.buildDirectory.dir("monomer-product-bundle-verification").get().asFile
        verificationRoot.deleteRecursively()
        copy {
            from(zipTree(archive))
            into(verificationRoot)
        }
        check(verificationRoot.listFiles()?.map { it.name }?.toSet() == setOf("maven-repository")) {
            "Product bundle extraction must have exactly one root directory"
        }
        val markerFile = File(verificationRoot, "maven-repository/.monomer-metricflow-manifest.json")
        val marker = JsonSlurper().parse(markerFile) as? Map<*, *>
            ?: error("Product bundle marker must be a JSON object")
        val expectedMarkerKeys = setOf(
            "kind",
            "schemaVersion",
            "version",
            "artifactSetDigest",
            "source",
            "repositoryRoot",
            "productModuleAllowlist",
            "excludedModules",
            "artifacts",
        )
        check(marker.keys == expectedMarkerKeys) { "Product bundle marker shape changed: ${marker.keys}" }
        check(marker["kind"] == "cc.monomer.metricflow.maven-repository")
        check(marker["schemaVersion"] == 1)
        check(marker["version"] == project.version.toString())
        check(marker["repositoryRoot"] == "maven-repository")
        check(marker["productModuleAllowlist"] == monomerProductArtifactIds.sorted())
        check(marker["excludedModules"] == monomerProductExcludedModules)

        val expectedRepositoryArtifacts = monomerProductArtifactIds.map {
            "maven-repository/cc/monomer/metricflow/$it"
        }.toSet()
        val actualRepositoryArtifacts = File(verificationRoot, "maven-repository/cc/monomer/metricflow")
            .listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.relativeTo(verificationRoot).path.replace(File.separatorChar, '/') }
            ?.toSet()
            ?: emptySet()
        check(actualRepositoryArtifacts == expectedRepositoryArtifacts) {
            "Product repository artifacts differ. expected=$expectedRepositoryArtifacts actual=$actualRepositoryArtifacts"
        }

        val markerSource = marker["source"] as? Map<*, *>
            ?: error("Product bundle marker source must be an object")
        check(markerSource == mapOf("uri" to metricFlowSourceUri, "commit" to gitRevision())) {
            "Product bundle marker source does not match the verified checkout"
        }
        val expectedArtifacts = monomerProductArtifactIds.sorted().map { artifactId ->
            val relativePath = "cc/monomer/metricflow/$artifactId/${project.version}/$artifactId-${project.version}.jar"
            val jar = File(verificationRoot, "maven-repository/$relativePath")
            check(jar.isFile) { "Product bundle is missing primary JAR $relativePath" }
            mapOf(
                "coordinate" to "${project.group}:$artifactId:${project.version}",
                "relativePath" to relativePath,
                "sha256" to sha256Digest(jar),
            )
        }
        check(marker["artifacts"] == expectedArtifacts) {
            "Product bundle marker artifact set does not match the Maven repository"
        }
        val expectedIdentity = mapOf(
            "schemaVersion" to 1,
            "version" to project.version.toString(),
            "source" to markerSource,
            "artifacts" to expectedArtifacts.map { artifact ->
                mapOf(
                    "coordinate" to artifact.getValue("coordinate"),
                    "sha256" to artifact.getValue("sha256"),
                )
            },
        )
        check(marker["artifactSetDigest"] == canonicalSha256(expectedIdentity)) {
            "Product bundle marker artifactSetDigest is not canonical"
        }
        val forbiddenPaths = Files.walk(verificationRoot.toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) }
                .map { it.toString().replace(File.separatorChar, '/') }
                .filter {
                    it.contains("metricflow-render-duckdb") ||
                        it.contains("metricflow-grpc-server") ||
                        it.contains("internal-diff-runner")
                }
                .toList()
        }
        check(forbiddenPaths.isEmpty()) { "Product bundle contains excluded artifacts: $forbiddenPaths" }
        val releaseAssetsRoot = monomerProductReleaseAssetsRoot.get().asFile
        val staticEvidenceFiles = listOf(
            "$monomerProductBundleName.manifest.json",
            "$monomerProductBundleName.sbom.cyclonedx.json",
            "$monomerProductBundleName.dependency-evidence.json",
            "$monomerProductBundleName.license-evidence.json",
            "$monomerProductBundleName.provenance-input.json",
        )
        check(staticEvidenceFiles.all { File(releaseAssetsRoot, it).isFile }) {
            "Product release evidence is incomplete"
        }
        releaseAssetsRoot.mkdirs()
        File(releaseAssetsRoot, "${archive.name}.sha256").writeText(
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
