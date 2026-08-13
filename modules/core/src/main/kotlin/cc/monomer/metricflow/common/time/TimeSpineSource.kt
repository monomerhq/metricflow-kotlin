package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.TimeSpineCustomGranularityColumn
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.bind.SqlTable

/**
 * A calendar table that backs time-based joins.
 *
 * Port of `metricflow_semantics.time.time_spine_source.TimeSpineSource`. Holds:
 *
 * - [sqlTable] — the physical table reference (`:domain:spec:bind.SqlTable`).
 * - [baseColumn] — name of the column that holds the standard-granularity timestamp.
 * - [baseGranularity] — granularity of that column (the smallest grain available from
 *   this spine).
 * - [customGranularities] — additional columns mapping the spine to custom grains
 *   (e.g. `fiscal_quarter`).
 *
 * **Note on `chooseTimeSpineSources`**: the Python static method
 * `choose_time_spine_sources(required_time_spine_specs, time_spine_sources)` takes a
 * `Sequence[TimeDimensionSpec]` — and `TimeDimensionSpec` lives in `:domain:spec` (W4
 * isn't porting that full module yet). We defer that one method to W7 (when the spec
 * family lands). Everything else is ported.
 */
data class TimeSpineSource(
    val sqlTable: SqlTable,
    /** Name of the column in the table that contains date/time values that map to a standard granularity. */
    val baseColumn: String,
    /** The time granularity of [baseColumn]. */
    val baseGranularity: TimeGranularity,
    val customGranularities: List<TimeSpineCustomGranularityColumn>,
) {

    /** Description shown when this time spine is used in a data set. */
    val dataSetDescription: String get() = "Read From Time Spine '${sqlTable.tableName}'"

    /** Names of custom grains defined in this time spine. */
    val customGrainNames: List<String> get() = customGranularities.map { it.name }

    companion object {
        /** Default time granularity for time spines when the manifest doesn't specify one. */
        val DEFAULT_TIME_GRANULARITY: TimeGranularity = TimeGranularity.DAY

        /** The default `ds` column name Python's `TimeSpineSource` falls back to. */
        const val DEFAULT_BASE_COLUMN: String = "ds"

        /**
         * Build the set of standard time-spine sources from a [SemanticManifest].
         *
         * Port of `TimeSpineSource.build_standard_time_spine_sources`.
         *
         * 1. Pull each `time_spines` entry into a `(grain → TimeSpineSource)` map.
         * 2. For backwards compatibility, walk legacy `time_spine_table_configurations` and
         *    fill in any grain the new config didn't already cover.
         *
         * An empty map is valid. Time-spine requirements belong to query paths that actually
         * use time semantics; manifest lookup must remain constructible for atemporal queries.
         */
        fun buildStandardTimeSpineSources(
            semanticManifest: SemanticManifest,
        ): Map<TimeGranularity, TimeSpineSource> {
            val timeSpineSources: MutableMap<TimeGranularity, TimeSpineSource> = LinkedHashMap()

            for (timeSpine in semanticManifest.projectConfiguration.timeSpines) {
                val grain = timeSpine.primaryColumn.timeGranularity
                // Python's `NodeRelation.__create_default_relation_name` Pydantic validator
                // auto-builds `relation_name` from `db + schema + alias` when the JSON omits
                // it. The Kotlin manifest model (W1) explicitly skipped that quirk; we
                // reconstruct on demand here so the time-spine source can be built from a
                // bare `(schema, alias)` node-relation (e.g. `minimal_valid_manifest`).
                val relationName = timeSpine.nodeRelation.relationName.takeIf { it.isNotEmpty() }
                    ?: buildString {
                        timeSpine.nodeRelation.database?.takeIf { it.isNotEmpty() }?.let {
                            append(it); append('.')
                        }
                        append(timeSpine.nodeRelation.schemaName)
                        append('.')
                        append(timeSpine.nodeRelation.alias)
                    }
                timeSpineSources[grain] = TimeSpineSource(
                    sqlTable = SqlTable.fromString(relationName),
                    baseColumn = timeSpine.primaryColumn.name,
                    baseGranularity = timeSpine.primaryColumn.timeGranularity,
                    customGranularities = timeSpine.customGranularities.map { custom ->
                        TimeSpineCustomGranularityColumn(
                            name = custom.name,
                            columnName = custom.columnName,
                        )
                    },
                )
            }

            for (legacy in semanticManifest.projectConfiguration.timeSpineTableConfigurations) {
                if (legacy.grain !in timeSpineSources) {
                    timeSpineSources[legacy.grain] = TimeSpineSource(
                        sqlTable = SqlTable.fromString(legacy.location),
                        baseColumn = legacy.columnName,
                        baseGranularity = legacy.grain,
                        customGranularities = emptyList(),
                    )
                }
            }

            return timeSpineSources
        }

        /**
         * Build a `custom-grain-name → TimeSpineSource` lookup from the standard map.
         *
         * Port of `TimeSpineSource.build_custom_time_spine_sources`. Python uses an LRU
         * cache decorator — we omit caching here; callers cache at the lookup boundary.
         */
        fun buildCustomTimeSpineSources(
            timeSpineSources: List<TimeSpineSource>,
        ): Map<String, TimeSpineSource> = buildMap {
            for (timeSpineSource in timeSpineSources) {
                for (customGranularity in timeSpineSource.customGranularities) {
                    put(customGranularity.name, timeSpineSource)
                }
            }
        }

        /**
         * Build the set of supported custom granularities, each lifted to an [ExpandedTimeGranularity].
         *
         * Port of `TimeSpineSource.build_custom_granularities`.
         */
        fun buildCustomGranularities(
            timeSpineSources: Iterable<TimeSpineSource>,
        ): Map<String, ExpandedTimeGranularity> = buildMap {
            for (timeSpineSource in timeSpineSources) {
                for (customGranularity in timeSpineSource.customGranularities) {
                    put(
                        customGranularity.name,
                        ExpandedTimeGranularity(
                            name = customGranularity.name,
                            baseGranularity = timeSpineSource.baseGranularity,
                        ),
                    )
                }
            }
        }
    }
}
