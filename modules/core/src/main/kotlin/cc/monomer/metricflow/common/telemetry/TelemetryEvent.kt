package cc.monomer.metricflow.common.telemetry

import kotlinx.serialization.Serializable

/** Schema string echoed into every telemetry payload — matches `metricflow.telemetry.models.EVENT_SCHEMA`. */
const val TELEMETRY_EVENT_SCHEMA: String = "v1.0"

/**
 * Sealed family of telemetry events. Port of
 * `metricflow.telemetry.models.{TelemetryEvent, FunctionStartEvent, FunctionEndEvent}`.
 *
 * Python uses an abstract base class plus two concrete dataclasses. We use a
 * sealed interface so exhaustive `when` is available at the call site.
 */
@Serializable
sealed interface TelemetryEvent {
    val eventName: String
    val eventSchema: String
    /** ISO-8601 instant string. We avoid pulling kotlinx-datetime in for this leaf module. */
    val eventTime: String
    val levelName: String
}

/** Start of a function call. */
@Serializable
data class FunctionStartEvent(
    val invocationId: String,
    val moduleName: String,
    val functionName: String,
    override val eventTime: String,
    override val levelName: String,
) : TelemetryEvent {
    override val eventName: String get() = TelemetryEventName.FUNCTION_START.name
    override val eventSchema: String get() = TELEMETRY_EVENT_SCHEMA
}

/** Function call end — adds runtime and an optional exception trace. */
@Serializable
data class FunctionEndEvent(
    val invocationId: String,
    val moduleName: String,
    val functionName: String,
    val runtimeSeconds: Double,
    val exceptionTrace: String?,
    override val eventTime: String,
    override val levelName: String,
) : TelemetryEvent {
    override val eventName: String get() = TelemetryEventName.FUNCTION_END.name
    override val eventSchema: String get() = TELEMETRY_EVENT_SCHEMA
}

/** Names of all possible telemetry events. */
enum class TelemetryEventName {
    FUNCTION_START,
    FUNCTION_END,
}

/**
 * Payload that can be easily serialized to JSON.
 *
 * Port of `metricflow.telemetry.models.TelemetryPayload`. Holds the
 * `client_id` plus an unbounded list of start / end events so that the
 * `TelemetryHandler` writes them as one document.
 */
@Serializable
data class TelemetryPayload(
    val clientId: String,
    val functionStartEvents: List<FunctionStartEvent>,
    val functionEndEvents: List<FunctionEndEvent>,
) {
    val payloadSchema: String get() = TELEMETRY_EVENT_SCHEMA
}
