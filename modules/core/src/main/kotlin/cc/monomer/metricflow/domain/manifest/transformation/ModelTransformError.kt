package cc.monomer.metricflow.domain.manifest.transformation

/**
 * Raised by a transformation rule when the manifest is malformed in a way the rule cannot
 * silently repair.
 *
 * Port of `metricflow_semantic_interfaces/errors.py::ModelTransformError`.
 *
 * Examples: a COUNT metric/measure that lacks an `expr`, a derived metric that references an
 * unknown sub-metric, an existing user metric whose name collides with a `create_metric=True`
 * measure but isn't a SIMPLE metric.
 */
class ModelTransformError(message: String) : RuntimeException(message)
