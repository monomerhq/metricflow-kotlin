package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * An immutable runtime time window.
 *
 * Port of `metricflow_semantics.specs.time_window.TimeWindow` (the runtime
 * counterpart of the manifest's
 * [cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow]).
 *
 * The Python class is built from `metricflow_semantic_interfaces.protocols.MetricTimeWindow`
 * via `create_from_dsi_time_window`. We provide the same conversion via
 * [createFromDsiTimeWindow].
 */
data class TimeWindow(val count: Int, val granularity: String) {

    /** Returns `true` if the window uses a standard [TimeGranularity]. */
    val isStandardGranularity: Boolean
        get() = STANDARD_GRANULARITY_NAMES.contains(granularity.lowercase())

    companion object {
        private val STANDARD_GRANULARITY_NAMES: Set<String> =
            TimeGranularity.entries.map { it.value.lowercase() }.toSet()

        /**
         * Construct a [TimeWindow] from a manifest-side
         * [cc.monomer.metricflow.domain.manifest.model.MetricTimeWindow].
         *
         * We accept `(count, granularity)` directly so this module doesn't
         * import the manifest's `MetricTimeWindow` type — the Python equivalent
         * `create_from_dsi_time_window` projects exactly these two fields.
         */
        fun createFromDsiTimeWindow(count: Int, granularity: String): TimeWindow =
            TimeWindow(count = count, granularity = granularity)
    }
}
