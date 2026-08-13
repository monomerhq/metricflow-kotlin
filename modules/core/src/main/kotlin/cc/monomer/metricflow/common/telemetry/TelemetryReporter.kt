package cc.monomer.metricflow.common.telemetry

import cc.monomer.metricflow.common.util.mfRandomId
import java.net.NetworkInterface
import java.security.MessageDigest
import java.time.Instant
import kotlin.time.TimeSource

/**
 * Reports telemetry for improving product experience.
 *
 * Port of `metricflow.telemetry.reporter.TelemetryReporter`. The reporter
 * fans out [FunctionStartEvent] / [FunctionEndEvent] records to every
 * registered [TelemetryHandler] when the configured threshold permits it.
 *
 * Construction matches the Python flow:
 * - `fullyAnonymous=true` → fixed [FULLY_ANONYMOUS_CLIENT_ID].
 * - `clientEmailOverride` (matches `METRICFLOW_CLIENT_EMAIL`) takes precedence next.
 * - Otherwise a SHA-256 of `<os.name>_<os.version>_<MAC>` becomes the client id.
 */
class TelemetryReporter(
    private val reportLevelsHigherOrEqualTo: TelemetryLevel,
    fullyAnonymous: Boolean,
    clientEmailOverride: String?,
) {

    private val testHandler = InMemoryTelemetryHandler()
    private val handlers: MutableList<TelemetryHandler> = mutableListOf()

    val clientId: String = when {
        fullyAnonymous -> FULLY_ANONYMOUS_CLIENT_ID
        !clientEmailOverride.isNullOrEmpty() -> clientEmailOverride
        else -> createClientId()
    }

    fun addHandler(handler: TelemetryHandler) {
        handlers.add(handler)
    }

    fun addTestHandler() {
        handlers.add(testHandler)
    }

    /** Used for testing — exposes events captured by the in-memory handler. */
    fun testPayloads(): List<TelemetryPayload> = testHandler.payloads

    /** Logs the start of a function call when the reporter's threshold is `USAGE` or lower. */
    fun logFunctionStart(invocationId: String, moduleName: String, functionName: String) {
        if (TelemetryLevel.USAGE.shouldReport(reportLevelsHigherOrEqualTo)) {
            val event = FunctionStartEvent(
                invocationId = invocationId,
                moduleName = moduleName,
                functionName = functionName,
                eventTime = Instant.now().toString(),
                levelName = TelemetryLevel.USAGE.name,
            )
            for (handler in handlers) {
                handler.log(clientId = clientId, functionStartEvent = event, functionEndEvent = null)
            }
        }
    }

    /** Logs the end of a function call; promotes to `EXCEPTION` level if a trace is supplied. */
    fun logFunctionEnd(
        invocationId: String,
        moduleName: String,
        functionName: String,
        runtimeSeconds: Double,
        exceptionTrace: String?,
    ) {
        val usageAllowed = TelemetryLevel.USAGE.shouldReport(reportLevelsHigherOrEqualTo)
        val exceptionAllowed =
            exceptionTrace != null && TelemetryLevel.EXCEPTION.shouldReport(reportLevelsHigherOrEqualTo)
        if (!usageAllowed && !exceptionAllowed) return

        val level = if (exceptionTrace != null) TelemetryLevel.EXCEPTION else TelemetryLevel.USAGE
        val event = FunctionEndEvent(
            invocationId = invocationId,
            moduleName = moduleName,
            functionName = functionName,
            runtimeSeconds = runtimeSeconds,
            exceptionTrace = exceptionTrace,
            eventTime = Instant.now().toString(),
            levelName = level.name,
        )
        for (handler in handlers) {
            handler.log(clientId = clientId, functionStartEvent = null, functionEndEvent = event)
        }
    }

    companion object {
        const val FULLY_ANONYMOUS_CLIENT_ID: String = "anonymous"
        const val ENV_EMAIL_OVERRIDE: String = "METRICFLOW_CLIENT_EMAIL"

        private fun createClientId(): String {
            val osName = System.getProperty("os.name") ?: "unknown"
            val osVersion = System.getProperty("os.version") ?: "unknown"
            val mac = primaryMacAddress() ?: "00:00:00:00:00:00"
            val rawId = "${osName}_${osVersion}_$mac"
            val digest = MessageDigest.getInstance("SHA-256").digest(rawId.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun primaryMacAddress(): String? {
            return runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    val hw = iface.hardwareAddress ?: continue
                    if (hw.isEmpty()) continue
                    return@runCatching hw.joinToString(":") { "%02x".format(it) }
                }
                null
            }.getOrNull()
        }
    }
}

/**
 * Helper equivalent to the Python `@log_call(...)` decorator.
 *
 * Runs [block] while logging function-start and function-end events on the
 * supplied [reporter]. Re-throws the original exception after recording the
 * exception trace in [FunctionEndEvent.exceptionTrace].
 */
inline fun <R> logCall(
    reporter: TelemetryReporter,
    moduleName: String,
    functionName: String,
    block: () -> R,
): R {
    val invocationId = "call_${mfRandomId()}"
    val mark = TimeSource.Monotonic.markNow()
    reporter.logFunctionStart(invocationId, moduleName, functionName)
    var trace: String? = null
    try {
        return block()
    } catch (t: Throwable) {
        trace = t.stackTraceToString()
        throw t
    } finally {
        val seconds = mark.elapsedNow().inWholeNanoseconds / 1_000_000_000.0
        reporter.logFunctionEnd(
            invocationId = invocationId,
            moduleName = moduleName,
            functionName = functionName,
            runtimeSeconds = seconds,
            exceptionTrace = trace,
        )
    }
}
