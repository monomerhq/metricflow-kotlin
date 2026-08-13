package cc.monomer.metricflow.common.time

import java.time.LocalDateTime

/**
 * Provides time to classes that need a sense of time.
 *
 * Port of `metricflow_semantics.time.time_source.TimeSource`. A static
 * implementation can be used for tests; a clock-backed implementation is
 * used in production by `application.engine`.
 */
fun interface TimeSource {
    fun getTime(): LocalDateTime
}
