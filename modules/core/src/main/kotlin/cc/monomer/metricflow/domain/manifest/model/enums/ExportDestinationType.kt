package cc.monomer.metricflow.domain.manifest.model.enums

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Types of destinations that exports can be written to.
 *
 * Port of `metricflow_semantic_interfaces/type_enums/export_destination_type.py::ExportDestinationType`.
 */
@Serializable
enum class ExportDestinationType(val value: String) {
    @SerialName("table") TABLE("table"),
    @SerialName("view") VIEW("view"),
}
