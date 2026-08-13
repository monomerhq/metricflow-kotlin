package cc.monomer.metricflow.common.telemetry

/**
 * Determines the level of a telemetry event.
 *
 * Port of `metricflow.telemetry.models.TelemetryLevel`. The numeric values
 * are preserved so that ordering comparisons (`USAGE >= OFF` etc.) keep the
 * same semantics as the Python `IntEnum`.
 */
enum class TelemetryLevel(val numericValue: Int) {
    USAGE(10),
    ERROR(20),
    EXCEPTION(30),
    OFF(40),
    ;

    /** Whether events at this level should be reported when the reporter is configured for [threshold]. */
    fun shouldReport(threshold: TelemetryLevel): Boolean = numericValue >= threshold.numericValue
}
