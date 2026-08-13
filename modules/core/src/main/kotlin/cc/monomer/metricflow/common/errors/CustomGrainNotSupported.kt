package cc.monomer.metricflow.common.errors

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity

/**
 * Casts an input granularity string to [TimeGranularity], otherwise throws.
 *
 * Port of `metricflow_semantics.errors.custom_grain_not_supported.error_if_not_standard_grain`.
 * The Python wrapper exists so call sites with not-yet-supported custom
 * grains fail loudly with a uniform error message.
 */
fun errorIfNotStandardGrain(inputGranularity: String, context: String?): TimeGranularity {
    return try {
        TimeGranularity.fromString(inputGranularity)
    } catch (ex: IllegalArgumentException) {
        val baseMsg =
            "Received a non-standard time granularity, which is not supported at the moment, " +
                "received: $inputGranularity."
        val fullMsg = if (context != null) "$baseMsg\nContext: $context" else baseMsg
        throw FeatureNotSupportedError(fullMsg)
    }
}
