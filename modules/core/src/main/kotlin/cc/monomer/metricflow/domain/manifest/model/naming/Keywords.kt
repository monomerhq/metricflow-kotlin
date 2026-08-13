package cc.monomer.metricflow.domain.manifest.model.naming

/**
 * MetricFlow naming keywords.
 *
 * Port of `metricflow_semantic_interfaces/naming/keywords.py`.
 */

/** A double underscore used as a separator in group-by item names. e.g. `user__country`. */
const val DUNDER: String = "__"

/** The name for the time dimension used to tabulate / plot metrics. */
const val METRIC_TIME_ELEMENT_NAME: String = "metric_time"

/** Returns true if the given element name corresponds to metric time. */
fun isMetricTimeName(elementName: String): Boolean = elementName == METRIC_TIME_ELEMENT_NAME
