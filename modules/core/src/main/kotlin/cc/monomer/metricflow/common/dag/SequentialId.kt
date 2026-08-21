package cc.monomer.metricflow.common.dag

/**
 * A sequentially numbered ID — formatted as `<prefix>_<index>`.
 *
 * Port of `metricflow_semantics.dag.sequential_id.SequentialId`. The Python
 * type's `__repr__` returns the same string as [strValue], which is used as
 * the human-readable node identifier in DAG dumps and snapshot SQL.
 */
data class SequentialId(val idPrefix: IdPrefix, val index: Int) {
    val strValue: String get() = "${idPrefix.strValue}_$index"
    override fun toString(): String = strValue
}

/**
 * Generates sequential ID values keyed by [IdPrefix].
 *
 * Port of `metricflow_semantics.dag.sequential_id.SequentialIdGenerator`. The
 * Python class uses a `ContextVar` to make ID generation stack-aware (so
 * tests can pin IDs to a deterministic start value); Kotlin uses a
 * [ThreadLocal] stack to match the semantics.
 */
object SequentialIdGenerator {

    private data class State(
        val defaultStartValue: Int,
        val prefixToNextValue: Map<String, Int>,
    )

    private val stack: ThreadLocal<ArrayDeque<State>> = ThreadLocal.withInitial {
        ArrayDeque<State>().apply { add(State(defaultStartValue = 0, prefixToNextValue = emptyMap())) }
    }

    private fun currentState(): State = stack.get().last()
    private fun replaceTop(state: State) {
        val list = stack.get()
        list.removeLast()
        list.add(state)
    }

    /** Returns the next sequential ID under the given [idPrefix]. */
    fun createNextId(idPrefix: IdPrefix): SequentialId {
        val state = currentState()
        val prefixValue = idPrefix.strValue
        val nextIndex = state.prefixToNextValue[prefixValue] ?: state.defaultStartValue
        val newState = state.copy(prefixToNextValue = state.prefixToNextValue + (prefixValue to nextIndex + 1))
        replaceTop(newState)
        return SequentialId(idPrefix, nextIndex)
    }

    /** Resets the per-prefix counters so that the next call to [createNextId] starts at [defaultStartValue]. */
    fun reset(defaultStartValue: Int) {
        replaceTop(State(defaultStartValue, emptyMap()))
    }

    /** Resets to a default start value of 0. */
    fun reset() {
        reset(0)
    }

    /**
     * Opens a scope where ID generation starts at [startValue] and reverts on exit.
     *
     * Mirrors the Python `with SequentialIdGenerator.id_number_space(start_value):` context manager.
     */
    inline fun <R> idNumberSpace(startValue: Int, block: () -> R): R {
        pushState(startValue)
        try {
            return block()
        } finally {
            popState()
        }
    }

    @PublishedApi
    internal fun pushState(startValue: Int) {
        stack.get().add(State(defaultStartValue = startValue, prefixToNextValue = emptyMap()))
    }

    @PublishedApi
    internal fun popState() {
        val list = stack.get()
        if (list.size <= 1) return
        list.removeLast()
    }
}
