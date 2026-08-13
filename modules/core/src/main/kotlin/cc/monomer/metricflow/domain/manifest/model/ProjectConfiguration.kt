package cc.monomer.metricflow.domain.manifest.model

import kotlinx.serialization.Serializable

/**
 * Manifest-wide configuration: time-spine settings + the version of the producer.
 *
 * Port of `metricflow_semantic_interfaces/implementations/project_configuration.py::PydanticProjectConfiguration`.
 *
 * The Python side runs a `__create_default_dsi_package_version` validator that fills in the
 * `metricflow` package version when `dsi_package_version` is missing. We don't replicate that
 * here — the corpus manifests always carry an explicit version, and re-emitting a fabricated
 * version would break round-trip equality.
 */
@Serializable
data class ProjectConfiguration(
    val timeSpineTableConfigurations: List<TimeSpineTableConfiguration> = emptyList(),
    val metadata: Metadata? = null,
    val dsiPackageVersion: SemanticVersion = SemanticVersion.UNKNOWN_VERSION_SENTINEL,
    val timeSpines: List<TimeSpine> = emptyList(),
)
