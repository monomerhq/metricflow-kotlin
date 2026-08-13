package cc.monomer.metricflow.domain.query.group_by.resolution_dag

import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference

/**
 * Identifies an input-metric slot inside a derived metric's definition.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.input_metric_location.InputMetricDefinitionLocation`.
 *
 * Used by the resolution DAG to disambiguate "the same metric reference,
 * used twice in the same derived metric" — e.g. a moving-average that
 * references `bookings` at two different offsets.
 */
data class InputMetricDefinitionLocation(
    val derivedMetricReference: MetricReference,
    val inputMetricListIndex: Int,
) {
    /**
     * Look up the associated [MetricInput] from a [MetricLookup].
     *
     * Throws [IllegalArgumentException] when the configured index is out
     * of range — that indicates a stale resolution DAG referencing a
     * manifest revision that no longer contains the same inputs.
     */
    fun getMetricInput(metricLookup: MetricLookup): MetricInput {
        val metric = metricLookup.getMetric(derivedMetricReference)
        val inputs = MetricLookup.metricInputs(metric, includeConversionMetricInput = false)
        if (inputMetricListIndex >= inputs.size) {
            throw IllegalArgumentException(
                "The metric-input list index $inputMetricListIndex is out of range for metric=$metric.",
            )
        }
        return inputs[inputMetricListIndex]
    }
}
