package cc.monomer.metricflow.domain.query.filter

import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocation
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Build a [MetricSpec] from a manifest-side [MetricInput] using the
 * [WhereFilterSpecFactory] from `:domain:query`.
 *
 * Port of `metricflow_semantics.specs.metric_spec.MetricSpec.create_from_input_metric`.
 *
 * The Python factory lives on `MetricSpec` itself, but the Kotlin port
 * can't host it there: `:domain:spec` cannot see [WhereFilterSpecFactory]
 * (which depends on `:domain:semantic-graph`). W8 places the factory
 * here, next to its driver. Downstream `:domain:dataflow` (W9) code
 * should import `MetricSpecFactory.createFromInputMetric` rather than the
 * Python-side companion method.
 */
object MetricSpecFactory {

    /**
     * Mirrors Python:
     * ```
     * @staticmethod
     * def create_from_input_metric(
     *     metric_input: MetricInput,
     *     filter_spec_factory: WhereFilterSpecFactory,
     *     additional_filter_specs: Optional[Iterable[WhereFilterSpec]] = None,
     * ) -> MetricSpec
     * ```
     */
    fun createFromInputMetric(
        metricInput: MetricInput,
        filterSpecFactory: WhereFilterSpecFactory,
        additionalFilterSpecs: List<WhereFilterSpec>,
    ): MetricSpec {
        val filterSpecs = mutableListOf<WhereFilterSpec>()
        val metricInputFilter = metricInput.filter
        if (metricInputFilter != null) {
            filterSpecs += filterSpecFactory.createFromWhereFilterIntersection(
                filterLocation = WhereFilterLocation.forInputMetric(metricInput.asReference),
                filterIntersection = metricInputFilter,
            )
        }
        filterSpecs += additionalFilterSpecs

        val offsetWindow = metricInput.offsetWindow?.let {
            TimeWindow(count = it.count, granularity = it.granularity)
        }
        val offsetToGrain = metricInput.offsetToGrain?.let { TimeGranularity.fromString(it) }

        return MetricSpec(
            elementName = metricInput.name,
            whereFilterSpecs = filterSpecs,
            alias = metricInput.alias,
            offsetWindow = offsetWindow,
            offsetToGrain = offsetToGrain,
        )
    }
}
