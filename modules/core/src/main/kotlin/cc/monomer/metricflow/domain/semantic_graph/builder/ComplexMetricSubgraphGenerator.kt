package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.common.errors.InvalidManifestException
import cc.monomer.metricflow.common.graph.MetricFlowGraphLabel
import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.OrderedSet
import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.MetricInput
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.semantic_graph.ComplexMetricNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.MetricDefinitionEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphNode
import cc.monomer.metricflow.domain.semantic_graph.SimpleMetricNode
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AttributeRecipeStep
import cc.monomer.metricflow.domain.semantic_graph.edge.ConversionMetricLabel
import cc.monomer.metricflow.domain.semantic_graph.edge.CumulativeMetricLabel
import cc.monomer.metricflow.domain.semantic_graph.edge.DenyDatePartLabel
import cc.monomer.metricflow.domain.semantic_graph.edge.DenyEntityKeyQueryResolutionLabel
import cc.monomer.metricflow.domain.semantic_graph.edge.DenyVisibleAttributesLabel

private enum class ComplexMetricSpecialCase {
    CONVERSION_INPUT_METRIC,
    CUMULATIVE_METRIC,
    CUMULATIVE_METRIC_WITH_WINDOW_OR_GRAIN_TO_DATE,
    TIME_OFFSET_DERIVED_METRIC,
}

/**
 * Generates the subgraph that models the relationship between complex metrics
 * and their inputs.
 *
 * Port of `ComplexMetricSubgraphGenerator`.
 *
 * For each complex metric, walks its [Metric.inputMetrics] and emits
 * [MetricDefinitionEdge]s carrying the appropriate edge labels and recipe
 * steps for the special cases (cumulative window/grain, conversion metric,
 * time-offset derived).
 */
class ComplexMetricSubgraphGenerator(manifestObjectLookup: ManifestObjectLookup) :
    SemanticSubgraphGenerator(manifestObjectLookup) {

    private val emptyLabels: OrderedSet<MetricFlowGraphLabel> = FrozenOrderedSet()

    private val specialCaseToLabels: Map<ComplexMetricSpecialCase, OrderedSet<MetricFlowGraphLabel>> = run {
        val cumulativeCommon: OrderedSet<MetricFlowGraphLabel> =
            FrozenOrderedSet(listOf(CumulativeMetricLabel, DenyDatePartLabel))
        mapOf(
            ComplexMetricSpecialCase.CONVERSION_INPUT_METRIC to
                FrozenOrderedSet(listOf<MetricFlowGraphLabel>(DenyVisibleAttributesLabel)),
            ComplexMetricSpecialCase.CUMULATIVE_METRIC to cumulativeCommon,
            ComplexMetricSpecialCase.CUMULATIVE_METRIC_WITH_WINDOW_OR_GRAIN_TO_DATE to
                cumulativeCommon.union(listOf(DenyEntityKeyQueryResolutionLabel)),
            ComplexMetricSpecialCase.TIME_OFFSET_DERIVED_METRIC to
                FrozenOrderedSet(listOf<MetricFlowGraphLabel>(DenyEntityKeyQueryResolutionLabel)),
        )
    }

    override fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>) {
        val metricNameToNode = mutableMapOf<String, SemanticGraphNode>()
        for (metric in manifestObjectLookup.getMetrics()) {
            addEdgesForAnyMetric(metric, metricNameToNode, edgeList)
        }
        // Conversion metric label is currently only referenced indirectly;
        // marking it as used so importing modules keep it visible.
        @Suppress("UNUSED_VARIABLE")
        val keepConversionLabel = ConversionMetricLabel
    }

    private fun addEdgesForComplexMetric(
        complexMetric: Metric,
        metricNameToNode: MutableMap<String, SemanticGraphNode>,
        edgeList: MutableList<SemanticGraphEdge>,
    ) {
        val metricType = complexMetric.type
        val inputMetricNameToLabels = mutableMapOf<String, OrderedSet<MetricFlowGraphLabel>>()
        var recipeStep = AttributeRecipeStep.EMPTY

        val inputMetrics: List<MetricInput> = MetricLookup.metricInputs(
            metric = complexMetric,
            includeConversionMetricInput = true,
        )

        when (metricType) {
            MetricType.SIMPLE -> Unit
            MetricType.CUMULATIVE -> {
                val cumulativeTypeParams = complexMetric.typeParams.cumulativeTypeParams
                    ?: throw InvalidManifestException(
                        "Expected cumulative_type_params to be set for a cumulative metric: $complexMetric",
                    )
                recipeStep = AttributeRecipeStep.EMPTY.copy(setDenyDatePart = true)
                val edgeLabels = if (
                    cumulativeTypeParams.window != null || cumulativeTypeParams.grainToDate != null
                ) {
                    specialCaseToLabels.getValue(
                        ComplexMetricSpecialCase.CUMULATIVE_METRIC_WITH_WINDOW_OR_GRAIN_TO_DATE,
                    )
                } else {
                    specialCaseToLabels.getValue(ComplexMetricSpecialCase.CUMULATIVE_METRIC)
                }
                val inputForCumulative = cumulativeTypeParams.metric
                    ?: throw InvalidManifestException(
                        "Expected metric to be set for a cumulative metric: $complexMetric",
                    )
                inputMetricNameToLabels[inputForCumulative.name] = edgeLabels
            }
            MetricType.RATIO -> Unit
            MetricType.CONVERSION -> {
                val conversionTypeParams = complexMetric.typeParams.conversionTypeParams
                    ?: throw InvalidManifestException(
                        "Expected conversion_type_params to be set for a conversion metric: $complexMetric",
                    )
                val conversionMetric = conversionTypeParams.conversionMetric
                    ?: throw InvalidManifestException(
                        "Expected conversion_metric to be set for a conversion metric: $complexMetric",
                    )
                inputMetricNameToLabels[conversionMetric.name] =
                    specialCaseToLabels.getValue(ComplexMetricSpecialCase.CONVERSION_INPUT_METRIC)
            }
            MetricType.DERIVED -> Unit
        }

        if (inputMetrics.isEmpty()) {
            throw RuntimeException(
                "This method should have been called with a metric that has input metrics: " +
                    "metric=${complexMetric.name}",
            )
        }

        val complexMetricNode = ComplexMetricNode.getInstance(complexMetric.name)
        var additionalEdgeLabels: OrderedSet<MetricFlowGraphLabel> = emptyLabels

        for (input in inputMetrics) {
            if (input.offsetWindow != null || input.offsetToGrain != null) {
                additionalEdgeLabels =
                    specialCaseToLabels.getValue(ComplexMetricSpecialCase.TIME_OFFSET_DERIVED_METRIC)
                break
            }
        }

        for (input in inputMetrics) {
            val name = input.name
            if (name !in metricNameToNode) {
                addEdgesForAnyMetric(manifestObjectLookup.getMetric(name), metricNameToNode, edgeList)
            }
            val inputNode = metricNameToNode.getValue(name)
            val combinedLabels = additionalEdgeLabels.union(
                FrozenOrderedSet(inputMetricNameToLabels[input.name] ?: emptyList()),
            )
            edgeList.add(
                MetricDefinitionEdge.create(
                    tailNode = complexMetricNode,
                    headNode = inputNode,
                    additionalLabels = combinedLabels,
                    recipeStep = recipeStep,
                ),
            )
        }
        metricNameToNode[complexMetric.name] = complexMetricNode
    }

    private fun addEdgesForAnyMetric(
        metric: Metric,
        metricNameToNode: MutableMap<String, SemanticGraphNode>,
        edgeList: MutableList<SemanticGraphEdge>,
    ) {
        when (metric.type) {
            MetricType.SIMPLE -> {
                metricNameToNode[metric.name] = SimpleMetricNode.getInstance(metric.name)
            }
            MetricType.RATIO,
            MetricType.CUMULATIVE,
            MetricType.CONVERSION,
            MetricType.DERIVED,
            -> addEdgesForComplexMetric(metric, metricNameToNode, edgeList)
        }
    }
}
