package cc.monomer.metricflow.domain.manifest.model

import kotlinx.serialization.Serializable

/**
 * Pydantic-style semantic version `major.minor.patch`.
 *
 * Port of `metricflow_semantic_interfaces/implementations/semantic_version.py::PydanticSemanticVersion`.
 *
 * Python uses string fields for each part (so leading zeros / non-numeric trailing labels
 * round-trip exactly); we keep the same shape.
 */
@Serializable
data class SemanticVersion(
    val majorVersion: String,
    val minorVersion: String,
    val patchVersion: String?,
) {
    companion object {
        /** Sentinel value used when the originator of a manifest didn't tag a version. */
        val UNKNOWN_VERSION_SENTINEL: SemanticVersion = SemanticVersion(
            majorVersion = "0",
            minorVersion = "0",
            patchVersion = "0",
        )

        /** Parse a string of the form `x.y` or `x.y.z` (or `x.y.z.w` — extra parts join into patch). */
        fun createFromString(versionStr: String): SemanticVersion {
            val parts = versionStr.split(".")
            if (parts.size < 2) {
                throw IllegalArgumentException(
                    "Expected version string to be of the form x.y or x.y.z, but got $versionStr",
                )
            }
            return SemanticVersion(
                majorVersion = parts[0],
                minorVersion = parts[1],
                patchVersion = if (parts.size >= 3) parts.subList(2, parts.size).joinToString(".") else null,
            )
        }
    }
}
