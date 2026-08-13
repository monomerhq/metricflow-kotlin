package cc.monomer.metricflow.common.time

import java.time.format.DateTimeFormatter

/**
 * ISO-8601 date format used across the engine.
 *
 * Port of `metricflow_semantics.time.time_constants.ISO8601_PYTHON_FORMAT`.
 */
const val ISO8601_DATE_FORMAT: String = "yyyy-MM-dd"

/**
 * ISO-8601 timestamp format used across the engine.
 *
 * Port of `metricflow_semantics.time.time_constants.ISO8601_PYTHON_TS_FORMAT`.
 */
const val ISO8601_TIMESTAMP_FORMAT: String = "yyyy-MM-dd HH:mm:ss"

/** Pre-built formatter matching [ISO8601_DATE_FORMAT]. */
val ISO8601_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(ISO8601_DATE_FORMAT)

/** Pre-built formatter matching [ISO8601_TIMESTAMP_FORMAT]. */
val ISO8601_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(ISO8601_TIMESTAMP_FORMAT)
