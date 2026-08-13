package cc.monomer.metricflow.common.util

import org.slf4j.LoggerFactory
import kotlin.time.TimeSource

/**
 * Times a section of code, optionally logging BEGIN/END markers around it.
 *
 * Port of `metricflow_semantics.toolkit.performance_helpers.ExecutionTimer`.
 * Python uses a context manager (`with ExecutionTimer(...): ...`); Kotlin
 * uses inline higher-order functions ([executionTimer]) so call sites read
 * `executionTimer("step") { … }`.
 */
class ExecutionTimer(val description: String?) {
    @PublishedApi
    internal var startMark: TimeSource.Monotonic.ValueTimeMark? = null
    @PublishedApi
    internal var totalNanos: Long = 0L

    fun begin() {
        startMark = TimeSource.Monotonic.markNow()
    }

    fun end() {
        val mark = startMark ?: return
        totalNanos += mark.elapsedNow().inWholeNanoseconds
        startMark = null
    }

    /** Duration accumulated across every completed [begin]/[end] pair. */
    val totalDuration: PrettyDuration
        get() = PrettyDuration(totalNanos / 1_000_000_000.0, PRETTY_DURATION_DEFAULT_DECIMALS)
}

@PublishedApi
internal val EXECUTION_TIMER_LOGGER = LoggerFactory.getLogger("metricflow.execution_timer")

/**
 * Runs [block] under an [ExecutionTimer] and returns the block's result.
 *
 * When [description] is non-null, logs `[ BEGIN ] description` / `[ END ] description in ...`
 * around the block at the SLF4J INFO level (matching Python's `log_level=logging.INFO`).
 */
inline fun <R> executionTimer(description: String?, block: (ExecutionTimer) -> R): R {
    val timer = ExecutionTimer(description)
    if (description != null) {
        EXECUTION_TIMER_LOGGER.info("[  BEGIN  ] {}", description)
    }
    timer.begin()
    try {
        return block(timer)
    } finally {
        timer.end()
        if (description != null) {
            EXECUTION_TIMER_LOGGER.info("[   END   ] {} in {}", description, timer.totalDuration)
        }
    }
}
