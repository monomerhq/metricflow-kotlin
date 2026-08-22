package cc.monomer.metricflow.common.errors

/**
 * Domain exception hierarchy for metricflow.
 *
 * Port of `metricflow_semantics.errors.error_classes`. Python ships these as
 * a deep `class X(Y)` chain — Kotlin uses `open class` to keep the same
 * extensibility while staying idiomatic.
 *
 * Conventions:
 * - [MetricFlowException] is the root.
 * - [InformativeUserError] is for actionable user-facing errors.
 * - [SemanticException] is for engine-internal semantic issues.
 */
open class MetricFlowException : RuntimeException {
    constructor() : super()
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
    constructor(cause: Throwable) : super(cause)
}

/** A non-recoverable error due to an issue within MetricFlow and not caused by the user. */
open class MetricFlowInternalError : MetricFlowException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/** Raised when an invalid manifest is detected outside the validator pipeline. */
open class InvalidManifestException(message: String) : MetricFlowException(message)

/** Raised when there is an error calling `graphviz` methods. */
open class GraphvizException(message: String) : MetricFlowException(message)

/** Raised for user errors that are actionable or otherwise informative. */
open class InformativeUserError : MetricFlowException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/** Customer-visible variant of [InformativeUserError]. */
open class CustomerFacingSemanticException(message: String) : InformativeUserError(message)

/** Indicates the engine cannot satisfy the query. The [context] map is rendered in the message. */
class UnableToSatisfyQueryError(
    val errorName: String,
    val context: Map<String, String>?,
) : CustomerFacingSemanticException(buildMessage(errorName, context)) {

    private companion object {
        fun buildMessage(errorName: String, context: Map<String, String>?): String {
            val sb = StringBuilder("Unable To Satisfy Query Error: ").append(errorName)
            if (context != null) {
                for ((k, v) in context) {
                    sb.append("\n").append(k).append(":\n")
                    sb.append(v.lineSequence().joinToString("\n") { "    $it" })
                }
            }
            return sb.toString()
        }
    }
}

/** Base class for engine-internal semantic exceptions. */
open class SemanticException : MetricFlowException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}

/** Two metric definitions share a name. */
class DuplicateMetricError(message: String) : SemanticException(message)

/** A referenced metric could not be located. */
class MetricNotFoundError(message: String) : SemanticException(message)

/** A semantic model declaration is malformed. */
class InvalidSemanticModelError(message: String) : SemanticException(message)

/** Raised if there are any errors while executing the execution plan. */
class ExecutionException(message: String) : MetricFlowException(message)

/** Engine feature is unsupported by the target data platform. */
class UnsupportedEngineFeatureError(message: String) : InformativeUserError(message)

/** Bind parameters supplied but the configured `SqlClient` does not support them. */
class SqlBindParametersNotSupportedError(message: String) : MetricFlowException(message)

/** User input named a metric the engine doesn't know. */
class UnknownMetricError(metricNames: List<String>) : InformativeUserError(buildMessage(metricNames)) {

    private companion object {
        fun buildMessage(metricNames: List<String>): String = when (metricNames.size) {
            0 -> throw RuntimeException("Can't create an UnknownMetricError without metric names")
            1 -> "Unknown metric: '${metricNames[0]}'"
            else -> "Unknown metrics: $metricNames"
        }
    }
}

/** Where-clause / query syntax invalid. */
class InvalidQuerySyntax(message: String) : InformativeUserError(message)

/** A query parameter set is malformed. */
open class InvalidQueryException(message: String) : InformativeUserError(message)

/** Error while rendering a SQL template. */
class RenderSqlTemplateException(message: String) : InformativeUserError(message)

/** A specific feature has not been implemented. */
open class FeatureNotSupportedError(message: String) : InformativeUserError(message)

/** Misconfigured semantic manifest. */
class SemanticManifestConfigurationError(message: String) : InformativeUserError(message)

/** A metric definition contains a dependency cycle or exceeds the supported dependency depth. */
class MetricDefinitionDependencyError(message: String) : InformativeUserError(message)
