package cc.monomer.metricflow.common.telemetry

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelemetryReporterTest {

    @Test
    fun `anonymous client id is fixed`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.USAGE,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        assertEquals(TelemetryReporter.FULLY_ANONYMOUS_CLIENT_ID, reporter.clientId)
    }

    @Test
    fun `email override is preferred`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.USAGE,
            fullyAnonymous = false,
            clientEmailOverride = "user@example.com",
        )
        assertEquals("user@example.com", reporter.clientId)
    }

    @Test
    fun `usage events flow through handlers when threshold is USAGE`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.USAGE,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        reporter.addTestHandler()
        reporter.logFunctionStart("call_1", "mod", "fn")
        reporter.logFunctionEnd("call_1", "mod", "fn", 1.5, null)

        val payloads = reporter.testPayloads()
        assertEquals(2, payloads.size)
        assertEquals(1, payloads[0].functionStartEvents.size)
        assertEquals(0, payloads[0].functionEndEvents.size)
        assertEquals("call_1", payloads[0].functionStartEvents[0].invocationId)

        assertEquals(0, payloads[1].functionStartEvents.size)
        assertEquals(1, payloads[1].functionEndEvents.size)
        val end = payloads[1].functionEndEvents[0]
        assertEquals(1.5, end.runtimeSeconds)
        assertNull(end.exceptionTrace)
        assertEquals(TelemetryLevel.USAGE.name, end.levelName)
    }

    @Test
    fun `function end with exception trace flips level to EXCEPTION`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.EXCEPTION,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        reporter.addTestHandler()
        // USAGE-level start does NOT pass the EXCEPTION threshold.
        reporter.logFunctionStart("call_2", "mod", "fn")
        // End with a trace passes because EXCEPTION >= EXCEPTION.
        reporter.logFunctionEnd("call_2", "mod", "fn", 0.25, "boom")

        val payloads = reporter.testPayloads()
        assertEquals(1, payloads.size, "start was filtered out, only end is recorded")
        val end = payloads.single().functionEndEvents.single()
        assertEquals("boom", end.exceptionTrace)
        assertEquals(TelemetryLevel.EXCEPTION.name, end.levelName)
    }

    @Test
    fun `OFF threshold suppresses everything`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.OFF,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        reporter.addTestHandler()
        reporter.logFunctionStart("call_3", "mod", "fn")
        reporter.logFunctionEnd("call_3", "mod", "fn", 0.0, "boom")
        assertTrue(reporter.testPayloads().isEmpty())
    }

    @Test
    fun `logCall records start and end`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.USAGE,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        reporter.addTestHandler()
        val result = logCall(reporter, moduleName = "test.module", functionName = "doWork") { 42 }
        assertEquals(42, result)
        val payloads = reporter.testPayloads()
        assertEquals(2, payloads.size)
        val startEvt = payloads[0].functionStartEvents.single()
        val endEvt = payloads[1].functionEndEvents.single()
        assertEquals(startEvt.invocationId, endEvt.invocationId)
        assertEquals("doWork", startEvt.functionName)
        assertNull(endEvt.exceptionTrace)
    }

    @Test
    fun `logCall propagates exception and records trace`() {
        val reporter = TelemetryReporter(
            reportLevelsHigherOrEqualTo = TelemetryLevel.USAGE,
            fullyAnonymous = true,
            clientEmailOverride = null,
        )
        reporter.addTestHandler()
        var thrown: Throwable? = null
        try {
            logCall(reporter, moduleName = "m", functionName = "fail") {
                error("expected")
            }
        } catch (t: Throwable) {
            thrown = t
        }
        assertNotNull(thrown)
        val end = reporter.testPayloads().last().functionEndEvents.single()
        val trace = end.exceptionTrace
        assertNotNull(trace)
        assertTrue(trace.contains("expected"))
    }

    @Test
    fun `telemetry level threshold ordering matches Python IntEnum`() {
        assertTrue(TelemetryLevel.USAGE.shouldReport(TelemetryLevel.USAGE))
        assertFalse(TelemetryLevel.USAGE.shouldReport(TelemetryLevel.ERROR))
        assertTrue(TelemetryLevel.EXCEPTION.shouldReport(TelemetryLevel.ERROR))
        assertFalse(TelemetryLevel.EXCEPTION.shouldReport(TelemetryLevel.OFF))
    }
}
