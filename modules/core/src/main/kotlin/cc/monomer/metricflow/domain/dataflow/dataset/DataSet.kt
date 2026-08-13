package cc.monomer.metricflow.domain.dataflow.dataset

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.dataflow.instance.InstanceSet
import cc.monomer.metricflow.domain.dataflow.instance.TimeDimensionInstance
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.spec.TimeDimensionSpec

/**
 * Describes a set of data that a source node in the dataflow plan contains.
 *
 * Port of `metricflow.dataset.dataset_classes.DataSet`. The Python class is abstract — only the
 * SQL subclass ([SqlDataSet]) is instantiated in production. This file also hosts the
 * `metric_time_*` static helpers that depend solely on the reserved keyword string.
 */
abstract class DataSet(val instanceSet: InstanceSet) {

    /**
     * Extracts all `metric_time` time-dimension instances from this dataset's [instanceSet].
     * Port of `DataSet.metric_time_dimension_instances`.
     */
    val metricTimeDimensionInstances: List<TimeDimensionInstance>
        get() = instanceSet.timeDimensionInstances.filter { it.spec.elementName == METRIC_TIME_ELEMENT_NAME }

    /**
     * Returns the metric-time instance used when a time-range constraint is requested.
     * Port of `DataSet.metric_time_instance_for_time_constraint`. Selects the instance whose
     * base granularity sorts smallest, skipping custom-grain and date-part variants.
     */
    val metricTimeInstanceForTimeConstraint: TimeDimensionInstance
        get() {
            val candidates = metricTimeDimensionInstances
                .filter { !it.spec.hasCustomGrain && it.spec.datePart == null }
                .sortedBy { it.spec.baseGranularitySortKey }
            check(candidates.isNotEmpty()) {
                "No metric time dimensions with standard granularities found in the input data set for this node"
            }
            return candidates[0]
        }

    /** If this dataset was created from a semantic model, return its reference. */
    abstract val semanticModelReference: SemanticModelReference?

    override fun toString(): String = "${this::class.simpleName}()"

    companion object {
        /**
         * Returns the reserved `metric_time` time-dimension reference. Port of
         * `DataSet.metric_time_dimension_reference`.
         */
        fun metricTimeDimensionReference(): TimeDimensionReference =
            TimeDimensionReference(elementName = METRIC_TIME_ELEMENT_NAME)

        /** Name form of [metricTimeDimensionReference]. */
        fun metricTimeDimensionName(): String = METRIC_TIME_ELEMENT_NAME

        /**
         * Build a [TimeDimensionSpec] for `metric_time` at the given grain. Port of
         * `DataSet.metric_time_dimension_spec` (granularity overload). The Kotlin
         * `TimeDimensionSpec` `init` requires exactly one of `timeGranularity` / `datePart`, so
         * we surface the choice as two explicit overloads instead of a Python-style
         * `Optional`+`Optional` pair.
         */
        fun metricTimeDimensionSpec(timeGranularity: ExpandedTimeGranularity): TimeDimensionSpec =
            TimeDimensionSpec(
                elementName = METRIC_TIME_ELEMENT_NAME,
                entityLinks = emptyList(),
                timeGranularity = timeGranularity,
                datePart = null,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )

        /** Build a [TimeDimensionSpec] for `metric_time` at the given date-part. */
        fun metricTimeDimensionSpec(datePart: DatePart): TimeDimensionSpec =
            TimeDimensionSpec(
                elementName = METRIC_TIME_ELEMENT_NAME,
                entityLinks = emptyList(),
                timeGranularity = null,
                datePart = datePart,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )
    }
}
