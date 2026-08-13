package cc.monomer.metricflow.common.time

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import kotlinx.serialization.Serializable

/**
 * Container for custom-granularity extensions to [TimeGranularity].
 *
 * Port of `metricflow_semantics.time.granularity.ExpandedTimeGranularity`.
 *
 * For standard granularities (e.g. `"month"`), [name] equals
 * `baseGranularity.value`. For custom grains, [name] is the custom-grain
 * identifier (e.g. `"fiscal_quarter"`) and [baseGranularity] is the
 * underlying standard grain used to look up the time spine.
 *
 * The invariant `if isStandardGranularityName(name) then base == TimeGranularity.fromString(name)`
 * is enforced in `init`, matching Python's `__post_init__`.
 */
@Serializable
data class ExpandedTimeGranularity(val name: String, val baseGranularity: TimeGranularity) :
    Comparable<ExpandedTimeGranularity> {

    init {
        if (isStandardGranularityName(name)) {
            val expected = TimeGranularity.fromString(name)
            require(expected == baseGranularity) {
                "Invalid configuration for standard TimeGranularity name '$name': " +
                    "base_granularity should be $expected, got $baseGranularity"
            }
        }
    }

    /** `true` iff this granularity has a name that differs from the standard one. */
    val isCustomGranularity: Boolean get() = baseGranularity.value != name

    override fun compareTo(other: ExpandedTimeGranularity): Int {
        val nameCmp = name.compareTo(other.name)
        if (nameCmp != 0) return nameCmp
        return baseGranularity.toInt().compareTo(other.baseGranularity.toInt())
    }

    companion object {
        private val STANDARD_GRANULARITY_NAMES: Set<String> =
            TimeGranularity.entries.map { it.value }.toSet()

        /** Returns `true` when [granularityName] matches one of the [TimeGranularity] enum values. */
        fun isStandardGranularityName(granularityName: String): Boolean =
            granularityName in STANDARD_GRANULARITY_NAMES

        /** Factory: lift a standard [TimeGranularity] to an [ExpandedTimeGranularity]. */
        fun fromTimeGranularity(granularity: TimeGranularity): ExpandedTimeGranularity =
            ExpandedTimeGranularity(name = granularity.value, baseGranularity = granularity)
    }
}
