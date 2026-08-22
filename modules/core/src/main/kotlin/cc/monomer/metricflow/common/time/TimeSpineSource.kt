package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.common.errors.SemanticManifestConfigurationError
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.TimeSpineCustomGranularityColumn
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec
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

        /**
         * Choose the smallest set of time spines that satisfies the requested time specs.
         *
         * Port of `TimeSpineSource.choose_time_spine_sources`. Custom grains are tied to the
         * spine that declares them. Standard grains can use any spine whose base grain is at
         * least as fine as every requested standard grain; among those compatible spines, the
         * coarsest one is selected to minimise joins and aggregation work. When a custom spine
         * cannot also satisfy the standard requirements, that coarsest compatible standard
         * spine is added as a second source.
         */
        fun chooseTimeSpineSources(
            requiredTimeSpineSpecs: Iterable<TimeDimensionSpec>,
            timeSpineSources: Map<TimeGranularity, TimeSpineSource>,
        ): List<TimeSpineSource> {
            val requiredSpecs = requiredTimeSpineSpecs.toList()
            require(requiredSpecs.isNotEmpty()) {
                "Choosing time spine source requires time spine specs, but the " +
                    "`requiredTimeSpineSpecs` parameter is empty. This indicates internal misconfiguration."
            }

            val customTimeSpines = buildCustomTimeSpineSources(timeSpineSources.values.toList())
            val requiredTimeSpines = linkedSetOf<TimeSpineSource>()
            for (spec in requiredSpecs) {
                if (spec.timeGranularity != null && spec.hasCustomGrain) {
                    requiredTimeSpines.add(customTimeSpines.getValue(spec.timeGranularity.name))
                }
            }

            // A date-part request has no explicit grain. DAY is the largest standard grain
            // that remains compatible with every supported date part.
            val smallestRequiredStandardGrain = requiredSpecs
                .map { spec -> spec.timeGranularity?.baseGranularity ?: TimeGranularity.DAY }
                .minBy { it.toInt() }
            val compatibleTimeSpinesForStandardGrains = timeSpineSources
                .filterKeys { grain -> grain.toInt() <= smallestRequiredStandardGrain.toInt() }

            if (compatibleTimeSpinesForStandardGrains.isEmpty()) {
                val smallestAvailable = timeSpineSources.keys
                    .minByOrNull { it.toInt() }
                    ?.name
                    ?: "none"
                throw SemanticManifestConfigurationError(
                    "This query requires a time spine with granularity " +
                        "${smallestRequiredStandardGrain.name} or smaller, which is not configured. " +
                        "The smallest available time spine granularity is $smallestAvailable, which is too large. " +
                        "See documentation for how to configure a new time spine: " +
                        "https://docs.getdbt.com/docs/build/metricflow-time-spine",
                )
            }

            // If the custom spine cannot satisfy the standard grains, add the coarsest
            // compatible standard spine. This mirrors Python's value-based source comparison.
            if (requiredTimeSpines.intersect(compatibleTimeSpinesForStandardGrains.values.toSet()).isEmpty()) {
                val largestCompatibleGrain = compatibleTimeSpinesForStandardGrains.keys
                    .maxBy { it.toInt() }
                requiredTimeSpines.add(compatibleTimeSpinesForStandardGrains.getValue(largestCompatibleGrain))
            }

            return requiredTimeSpines.sortedBy { it.baseGranularity.toInt() }
        }

        /** Sequence-shaped overload matching the upstream method's public input contract. */
        fun chooseTimeSpineSources(
            requiredTimeSpineSpecs: Sequence<TimeDimensionSpec>,
            timeSpineSources: Map<TimeGranularity, TimeSpineSource>,
        ): List<TimeSpineSource> = chooseTimeSpineSources(
            requiredTimeSpineSpecs = requiredTimeSpineSpecs.asIterable(),
            timeSpineSources = timeSpineSources,
        )
    }
}
