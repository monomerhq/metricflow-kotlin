package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.enums.ExportDestinationType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Configuration for an [Export] — where to write the data table to.
 *
 * Port of `metricflow_semantic_interfaces/implementations/export.py::PydanticExportConfig`.
 *
 * The Python side aliases the field `schema_name` to `schema` for YAML compatibility (`Field(alias="schema")`).
 * Manifest JSON always uses the `schema_name` key, so the alias is not needed here, but
 * we preserve the field name as `schemaName` for consistency with Python's data model.
 */
@Serializable
data class ExportConfig(
    val exportAs: ExportDestinationType,
    @SerialName("schema_name") val schemaName: String? = null,
    val alias: String? = null,
)

/**
 * An export: a named instruction to write the result of a saved query to a table or view.
 *
 * Port of `PydanticExport`.
 */
@Serializable
data class Export(
    val name: String,
    val config: ExportConfig,
)
