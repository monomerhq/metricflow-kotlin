package cc.monomer.metricflow.domain.metric_evaluation

import cc.monomer.metricflow.common.errors.errorIfNotStandardGrain
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricEvaluationPlan
import cc.monomer.metricflow.domain.plan_conversion.node_processor.PredicatePushdownState
import cc.monomer.metricflow.domain.query.filter.WhereFilterSpecFactory
import cc.monomer.metricflow.domain.query.resolution.WhereFilterLocation
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Abstract base for a metric evaluation planner.
 *
 * Port of `metricflow.metric_evaluation.metric_query_planner.MetricEvaluationPlanner`.
 *
 * Concrete subclasses ([DepthFirstSearchMetricEvaluationPlanner],
 * [PassThroughMetricEvaluationPlanner]) implement [buildPlan]; the shared
 * `_build_input_metric_specs_for_derived_metric` Python helper is exposed
 * here as a protected method for subclasses to call.
 */
abstract class MetricEvaluationPlanner(
    protected val manifestObjectLookup: ManifestObjectLookup,
    metricLookup: MetricLookup,
    @Suppress("unused") // Held for parity with Python ctor; subclasses may pull on it as the planner grows.
    protected val columnAssociationResolver: ColumnAssociationResolver,
) {

    protected val queryHelper: MetricQueryHelper = MetricQueryHelper(metricLookup)

    /** Build a metric evaluation plan for the requested metrics + group-by items. */
    abstract fun buildPlan(
        metricSpecs: List<MetricSpec>,
        groupByItemSpecs: List<LinkableInstanceSpec>,
        predicatePushdownState: PredicatePushdownState,
        filterSpecFactory: WhereFilterSpecFactory,
    ): MetricEvaluationPlan

    /**
     * For a metric that has input metrics (e.g. derived / ratio), return the
     * `MetricSpec`s for the input metrics with filters from the metric
     * definition and the calling spec composed in.
     *
     * Port of `MetricEvaluationPlanner._build_input_metric_specs_for_derived_metric`.
     */
    protected fun buildInputMetricSpecsForDerivedMetric(
        metricName: String,
        additionalFilterSpecs: Iterable<WhereFilterSpec>,
        filterSpecFactory: WhereFilterSpecFactory,
    ): List<MetricSpec> {
        val metric = manifestObjectLookup.getMetric(metricName)

        // The filter declared on the metric definition itself.
        val metricDefinitionFilterSpecs = filterSpecFactory.createFromWhereFilterIntersection(
            filterLocation = WhereFilterLocation.forMetric(MetricReference(metricName)),
            filterIntersection = metric.filter,
        )

        val inputMetricSpecs = mutableListOf<MetricSpec>()

        for (inputMetric in metric.inputMetrics) {
            val whereFilterSpecs = mutableListOf<WhereFilterSpec>()
            whereFilterSpecs.addAll(
                filterSpecFactory.createFromWhereFilterIntersection(
                    filterLocation = WhereFilterLocation.forInputMetric(inputMetric.asReference),
                    filterIntersection = inputMetric.filter,
                ),
            )
            whereFilterSpecs.addAll(metricDefinitionFilterSpecs)
            whereFilterSpecs.addAll(additionalFilterSpecs)

            val inputMetricOffsetToGrain = inputMetric.offsetToGrain?.let {
                errorIfNotStandardGrain(
                    inputGranularity = it,
                    context = "Metric(${metric.name}).InputMetric(${inputMetric.name}).offset_to_grain",
                )
            }

            val offsetWindow = inputMetric.offsetWindow?.let { window ->
                TimeWindow(
                    count = window.count,
                    granularity = window.granularity,
                )
            }

            val spec = MetricSpec.create(
                elementName = inputMetric.name,
                whereFilterSpecs = whereFilterSpecs,
                alias = inputMetric.alias,
                offsetWindow = offsetWindow,
                offsetToGrain = inputMetricOffsetToGrain,
            )
            inputMetricSpecs.add(spec)
        }
        return inputMetricSpecs
    }
}
