package cc.monomer.metricflow.common.util

/** Default number of decimals when formatting a [PrettyDuration] as text. */
const val PRETTY_DURATION_DEFAULT_DECIMALS: Int = 2

/**
 * Wrapper to format durations using the metricflow pretty-printer.
 *
 * Port of `metricflow_semantics.toolkit.time_helpers.PrettyDuration`. The
 * underlying value is plain seconds — the wrapper exists to keep call sites
 * readable (`pretty_duration` in log messages).
 */
data class PrettyDuration(val seconds: Double, val decimals: Int) {

    init {
        require(decimals >= 0) { "`decimals` must be >= 0 (got $decimals)" }
    }

    private val rendered: String = "%.${decimals}f s".format(seconds)

    override fun toString(): String = rendered

    companion object {
        /** Sum of every duration; the result keeps the largest decimal count. */
        fun sum(durations: Iterable<PrettyDuration>): PrettyDuration {
            val list = durations.toList()
            val total = list.sumOf { it.seconds }
            val maxDecimals = list.maxOfOrNull { it.decimals } ?: PRETTY_DURATION_DEFAULT_DECIMALS
            return PrettyDuration(total, maxDecimals)
        }
    }
}
