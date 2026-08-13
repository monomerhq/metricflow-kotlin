package cc.monomer.metricflow.common.telemetry

import org.slf4j.LoggerFactory

/**
 * Records telemetry to some destination.
 *
 * Port of `metricflow.telemetry.handlers.handlers.TelemetryHandler`. The
 * Python base class has a public `log(...)` that builds a payload then calls
 * the private `_write_log(...)` template-method. We keep the same two-step
 * shape: `log` builds the payload, `writePayload` is overridden by subclasses.
 */
abstract class TelemetryHandler {

    /** Subclasses implement this to dispatch the serialized payload somewhere. */
    protected abstract fun writePayload(payload: TelemetryPayload)

    /** Record a single start or end event under the given client identifier. */
    open fun log(
        clientId: String,
        functionStartEvent: FunctionStartEvent?,
        functionEndEvent: FunctionEndEvent?,
    ): Boolean {
        val payload = TelemetryPayload(
            clientId = clientId,
            functionStartEvents = listOfNotNull(functionStartEvent),
            functionEndEvents = listOfNotNull(functionEndEvent),
        )
        writePayload(payload)
        return true
    }
}

/**
 * Records telemetry events in-memory for tests.
 *
 * Port of `metricflow.telemetry.handlers.handlers.ToMemoryTelemetryHandler`.
 * Capped at 11 retained payloads (matching the Python guard).
 */
class InMemoryTelemetryHandler : TelemetryHandler() {

    private val _payloads = mutableListOf<TelemetryPayload>()

    val payloads: List<TelemetryPayload>
        get() = _payloads.toList()

    override fun writePayload(payload: TelemetryPayload) {
        if (_payloads.size > 10) {
            _payloads.removeAt(_payloads.size - 1)
        }
        _payloads.add(payload)
    }

    override fun log(
        clientId: String,
        functionStartEvent: FunctionStartEvent?,
        functionEndEvent: FunctionEndEvent?,
    ): Boolean {
        val payload = TelemetryPayload(
            clientId = clientId,
            functionStartEvents = listOfNotNull(functionStartEvent),
            functionEndEvents = listOfNotNull(functionEndEvent),
        )
        if (_payloads.size > 10) {
            _payloads.removeAt(_payloads.size - 1)
        }
        _payloads.add(payload)
        return true
    }
}

/**
 * Forwards every payload to an SLF4J logger at the given level.
 *
 * Port of `metricflow.telemetry.handlers.python_log.PythonLoggerTelemetryHandler`. The
 * Python handler uses the standard `logging` module; in Kotlin we delegate to
 * SLF4J which is the project's logging facade.
 */
class Slf4jTelemetryHandler(
    loggerName: String,
) : TelemetryHandler() {

    private val logger = LoggerFactory.getLogger(loggerName)

    override fun writePayload(payload: TelemetryPayload) {
        if (logger.isDebugEnabled) {
            logger.debug("telemetry: {}", payload)
        }
    }
}
