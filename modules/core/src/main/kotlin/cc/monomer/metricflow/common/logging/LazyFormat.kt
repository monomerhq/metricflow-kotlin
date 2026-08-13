package cc.monomer.metricflow.common.logging

/**
 * Lazily formats the given objects into a string representation for logging.
 *
 * Port of `metricflow_semantics.toolkit.mf_logging.lazy_formattable.LazyFormat`.
 * The formatting is deferred until [toString] is called so that log messages
 * at suppressed levels never pay for evaluation.
 *
 * Example:
 *
 * ```kotlin
 * logger.debug("{}", LazyFormat("Found path", mapOf("start" to start, "end" to end)))
 * ```
 *
 * Each value in [kwargs] may be either a literal value or a zero-arg lambda;
 * the lambda is invoked lazily during formatting (matching Python's
 * `Union[LoggedObject, Callable[[], LoggedObject]]`).
 */
class LazyFormat(
    private val messageTitle: Any,
    private val kwargs: Map<String, Any?>,
) {
    constructor(messageTitle: Any) : this(messageTitle, emptyMap())

    private val rendered: String by lazy(LazyThreadSafetyMode.NONE) {
        val title = evaluate(messageTitle).toString()
        if (kwargs.isEmpty()) return@lazy title
        val sb = StringBuilder(title)
        for ((k, v) in kwargs) {
            sb.append("\n    ").append(k).append(": ").append(mfPformat(evaluate(v)))
        }
        sb.toString()
    }

    override fun toString(): String = rendered

    private fun evaluate(value: Any?): Any? = when (value) {
        is Function0<*> -> value.invoke()
        else -> value
    }
}
