package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlinx.serialization.Serializable

/**
 * The primary time column of a [TimeSpine] table.
 *
 * Port of `metricflow_semantic_interfaces/implementations/time_spine.py::PydanticTimeSpinePrimaryColumn`.
 */
@Serializable
data class TimeSpinePrimaryColumn(
    val name: String,
    val timeGranularity: TimeGranularity,
)

/**
 * A custom-granularity column of a [TimeSpine] table — a user-defined grain (e.g. `fiscal_quarter`).
 *
 * Port of `PydanticTimeSpineCustomGranularityColumn`.
 */
@Serializable
data class TimeSpineCustomGranularityColumn(
    val name: String,
    val columnName: String? = null,
) {
    /** The actual column name in the time spine table. Defaults to [name] when [columnName] is absent. */
    val parsedColumnName: String get() = columnName ?: name
}

/**
 * A continuous-date table used for time-based joins.
 *
 * Port of `PydanticTimeSpine`.
 */
@Serializable
data class TimeSpine(
    val nodeRelation: NodeRelation,
    val primaryColumn: TimeSpinePrimaryColumn,
    val customGranularities: List<TimeSpineCustomGranularityColumn> = emptyList(),
)

/**
 * Legacy time-spine config (deprecated in favour of [TimeSpine]).
 *
 * Port of `metricflow_semantic_interfaces/implementations/time_spine_table_configuration.py::PydanticTimeSpineTableConfiguration`.
 */
@Serializable
data class TimeSpineTableConfiguration(
    val location: String,
    val columnName: String,
    val grain: TimeGranularity,
)
