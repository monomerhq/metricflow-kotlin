package cc.monomer.metricflow.domain.metric_evaluation.passthrough

import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.metric_evaluation.plan.ConversionMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.CumulativeMetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryElement
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryNode
import cc.monomer.metricflow.domain.metric_evaluation.plan.MetricQueryPropertySet
import cc.monomer.metricflow.domain.metric_evaluation.plan.SimpleMetricsQueryNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.TimeWindow
import cc.monomer.metricflow.domain.spec.where.WhereFilterSpec

/**
 * Builds [MetricQueryNode]s for [MetricQueryElement]s corresponding to non-derived metrics.
 *
 * Port of
 * `metricflow.metric_evaluation.passthrough.base_metric_query_node_builder.BaseMetricQueryNodeBuilder`.
 *
 * Each [MetricQueryElement] is grouped with others that share the same effective
 * evaluation context (model, filters, offsets, query properties). Compatible
 * simple-metric query elements on the same model are collapsed into one
 * [SimpleMetricsQueryNode] so a single SQL query computes both metrics.
 */
class BaseMetricQueryNodeBuilder(
    private val manifestObjectLookup: ManifestObjectLookup,
) {

    /**
     * Build [MetricQueryNode]s for the supplied base [MetricQueryElement]s.
     *
     * Throws if any of the elements correspond to a derived / ratio metric.
     */
    fun buildNodes(queryElements: Iterable<MetricQueryElement>): List<MetricQueryNode> {
        val groupKeyToSpecs = LinkedHashMap<BaseMetricsQueryElementGroupKey, MutableList<MetricSpec>>()
        for (queryElement in queryElements) {
            val key = buildGroupKey(queryElement)
            groupKeyToSpecs.getOrPut(key) { mutableListOf() }.add(queryElement.metricSpec)
        }

        val nodes = mutableListOf<MetricQueryNode>()
        for ((groupKey, groupedMetricSpecs) in groupKeyToSpecs) {
            val metricType = groupKey.metricType
            val queryProperties = groupKey.queryProperties
            when (metricType) {
                MetricType.SIMPLE -> {
                    val modelId = groupKey.modelId
                        ?: throw MetricFlowInternalError(
                            "Expected the group key for a simple metric to contain a model ID: groupKey=$groupKey",
                        )
                    nodes.add(
                        SimpleMetricsQueryNode.create(
                            modelId = modelId,
                            metricSpecs = groupedMetricSpecs,
                            queryProperties = queryProperties,
                        ),
                    )
                }
                MetricType.CUMULATIVE -> {
                    nodes.add(
                        CumulativeMetricQueryNode.create(
                            metricSpec = singleSpecForGroup(groupedMetricSpecs, metricType),
                            queryProperties = queryProperties,
                        ),
                    )
                }
                MetricType.CONVERSION -> {
                    nodes.add(
                        ConversionMetricQueryNode.create(
                            metricSpec = singleSpecForGroup(groupedMetricSpecs, metricType),
                            queryProperties = queryProperties,
                        ),
                    )
                }
                MetricType.RATIO, MetricType.DERIVED -> throw MetricFlowInternalError(
                    "Only base metrics should have been provided to this method " +
                        "(groupKey=$groupKey, metricSpecs=$groupedMetricSpecs)",
                )
            }
        }
        return nodes
    }

    private fun buildGroupKey(queryElement: MetricQueryElement): BaseMetricsQueryElementGroupKey {
        val metricName = queryElement.metricName
        val metricDefinition = manifestObjectLookup.getMetric(metricName)
        val metricSpec = queryElement.metricSpec
        val metricType = metricDefinition.type

        val modelId: SemanticModelId? = manifestObjectLookup.simpleMetricNameToInput[metricName]?.modelId

        val filtersFromMetricDefinition: List<String> = metricDefinition.filter
            ?.whereFilters
            ?.map { it.whereSqlTemplate }
            ?: emptyList()

        return BaseMetricsQueryElementGroupKey(
            modelId = modelId,
            metricType = metricType,
            nonSimpleMetricSpec = metricSpec.takeIf { metricType != MetricType.SIMPLE },
            aliasedMetricSpec = metricSpec.takeIf { metricSpec.alias != null },
            filtersFromMetricDefinition = filtersFromMetricDefinition,
            filtersFromMetricSpec = metricSpec.whereFilterSpecs,
            offsetWindowFromMetricSpec = metricSpec.offsetWindow,
            offsetToGrainFromMetricSpec = metricSpec.offsetToGrain,
            queryProperties = queryElement.queryProperties,
        )
    }

    private fun singleSpecForGroup(metricSpecs: List<MetricSpec>, metricType: MetricType): MetricSpec {
        if (metricSpecs.size != 1) {
            throw MetricFlowInternalError(
                "Expected exactly 1 metric spec for a non-groupable metric type " +
                    "(metricType=$metricType, metricSpecs=$metricSpecs)",
            )
        }
        return metricSpecs[0]
    }
}

/**
 * Grouping key for combining compatible base metric query elements into a
 * single [SimpleMetricsQueryNode] (when possible).
 *
 * Port of `BaseMetricsQueryElementGroupKey`.
 *
 * The fields encode every property that must match for two elements to share
 * a query node. Two simple metrics on the same model with identical filters /
 * offsets / properties can share; everything else gets its own node.
 */
data class BaseMetricsQueryElementGroupKey(
    /** Set on aliased metrics so each aliased metric maps to its own group. */
    val aliasedMetricSpec: MetricSpec?,
    /** Set on non-simple metrics so they always land in their own group. */
    val nonSimpleMetricSpec: MetricSpec?,
    /** Set on simple metrics so they can be grouped only with metrics on the same model. */
    val modelId: SemanticModelId?,
    val filtersFromMetricDefinition: List<String>,
    val filtersFromMetricSpec: List<WhereFilterSpec>,
    val offsetWindowFromMetricSpec: TimeWindow?,
    val offsetToGrainFromMetricSpec: cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity?,
    val queryProperties: MetricQueryPropertySet,
    /** Convenience — derived from the metric definition, retained on the key for hashing. */
    val metricType: MetricType,
)
