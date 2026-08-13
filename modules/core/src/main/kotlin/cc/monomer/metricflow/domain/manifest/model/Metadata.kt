package cc.monomer.metricflow.domain.manifest.model

import kotlinx.serialization.Serializable

/**
 * Parsing-source provenance for a manifest element.
 *
 * Port of `metricflow_semantic_interfaces/implementations/metadata.py::PydanticMetadata`.
 */
@Serializable
data class Metadata(
    val repoFilePath: String,
    val fileSlice: FileSlice,
)

/** A pointer into a source YAML/JSON file region from which an element was parsed. */
@Serializable
data class FileSlice(
    val filename: String,
    val content: String,
    val startLineNumber: Int,
    val endLineNumber: Int,
)
