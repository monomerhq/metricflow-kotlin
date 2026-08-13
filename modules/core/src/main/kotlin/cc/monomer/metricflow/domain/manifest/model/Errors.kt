package cc.monomer.metricflow.domain.manifest.model

/**
 * Domain exceptions raised when parsing a semantic manifest.
 *
 * Port of `metricflow_semantic_interfaces/errors.py`.
 */

/** Raised when a manifest cannot be parsed (malformed YAML/JSON, invalid window string, etc.). */
class ParsingException(message: String) : RuntimeException(message)
