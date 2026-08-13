package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Currently supported semantic manifest node types.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/semantic_manifest_node_type.py::SemanticManifestNodeType`.
 */
@Serializable
enum class SemanticManifestNodeType(val value: String) {
    @SerialName("metric") METRIC("metric"),
    @SerialName("saved_query") SAVED_QUERY("saved_query"),
    @SerialName("semantic_model") SEMANTIC_MODEL("semantic_model"),
    @SerialName("time_spine") TIME_SPINE("time_spine"),
}
