package cc.monomer.metricflow.domain.semantic_graph.lookup

import cc.monomer.metricflow.common.errors.InvalidManifestException
import cc.monomer.metricflow.common.errors.MetricFlowInternalError
import cc.monomer.metricflow.domain.manifest.model.Metric
import cc.monomer.metricflow.domain.manifest.model.SemanticModel
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInput
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInputAggregation
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInputNonAdditiveDimension

/**
 * Grouping key for the simple metrics on a semantic model.
 *
 * Port of `SimpleMetricAggregationConfiguration`.
 */
data class SimpleMetricAggregationConfiguration(
    val timeDimensionName: String,
    val timeGrain: TimeGranularity,
)

/**
 * Per-model lookup for simple metrics, in addition to the base
 * [ModelObjectLookup].
 *
 * Port of `metricflow_semantics/semantic_graph/lookups/simple_metric_model_object_lookup.py::SimpleMetricModelObjectLookup`.
 *
 * Constructs the [SimpleMetricInput] sequence for the simple metrics declared
 * on a given semantic model and indexes them by aggregation-time-grain.
 */
class SimpleMetricModelObjectLookup(
    semanticModel: SemanticModel,
    simpleMetrics: List<Metric>,
) : ModelObjectLookup(semanticModel) {

    init {
        if (simpleMetrics.isEmpty()) {
            throw MetricFlowInternalError(
                "Can't initialize with empty simple_metrics: simple_metrics=$simpleMetrics",
            )
        }
        for (metric in simpleMetrics) {
            if (metric.type != MetricType.SIMPLE) {
                throw MetricFlowInternalError(
                    "Can't initialize with a metric that is not a simple metric: metric=${metric.name}",
                )
            }
            val params = metric.typeParams.metricAggregationParams
            if (params == null || params.semanticModel != semanticModel.name) {
                throw MetricFlowInternalError(
                    "Can't initialize with a metric that is not associated with this semantic model: " +
                        "metric=${metric.name} model_name=${semanticModel.name}",
                )
            }
        }
    }

    private val timeDimensionNameToGrainPriv: Map<String, TimeGranularity> = buildMap {
        for (dimension in semanticModel.dimensions) {
            if (dimension.type == DimensionType.TIME) {
                val grain = dimension.typeParams?.timeGranularity ?: TimeGranularity.DAY
                put(dimension.name, grain)
            }
        }
    }

    private val simpleMetricInputsFromMetrics: List<SimpleMetricInput> = simpleMetrics.map { metric ->
        val metricTypeParams = metric.typeParams
        val metricAggregationParams = checkNotNull(metricTypeParams.metricAggregationParams) {
            "metric_aggregation_params should be set for all simple metrics"
        }

        val aggTimeDimensionName = metricAggregationParams.aggTimeDimension
            ?: semanticModel.defaults?.aggTimeDimension
            ?: throw InvalidManifestException(
                "Invalid aggregation time dimension configuration: metric=${metric.name} model=${semanticModel.name}",
            )
        val aggTimeDimensionGrain = timeDimensionNameToGrainPriv[aggTimeDimensionName]
            ?: throw InvalidManifestException(
                "Invalid aggregation time dimension configuration: " +
                    "metric=${metric.name} model=${semanticModel.name} time_dim=$aggTimeDimensionName",
            )

        SimpleMetricInput(
            name = metric.name,
            agg = metricAggregationParams.agg,
            expr = metricTypeParams.expr ?: metric.name,
            aggParams = SimpleMetricInputAggregation.createFromManifest(metricAggregationParams.aggParams),
            joinToTimespine = metricTypeParams.joinToTimespine,
            fillNullsWith = metricTypeParams.fillNullsWith,
            nonAdditiveDimension = SimpleMetricInputNonAdditiveDimension.createFromManifest(
                metricAggregationParams.nonAdditiveDimension,
            ),
            aggTimeDimensionName = aggTimeDimensionName,
            aggTimeDimensionGrain = aggTimeDimensionGrain,
            modelId = modelId,
            filter = metric.filter ?: WhereFilterIntersection(whereFilters = emptyList()),
        )
    }

    /** Mapping from aggregation-time-dimension name to the simple metric inputs that share it. */
    val aggregationTimeDimensionNameToSimpleMetricInputs: Map<String, List<SimpleMetricInput>> =
        simpleMetricInputsFromMetrics.groupBy { it.aggTimeDimensionName }

    /** Mapping from aggregation configuration (name + grain) to the simple metric inputs that share it. */
    val aggregationConfigurationToSimpleMetricInputs:
        Map<SimpleMetricAggregationConfiguration, List<SimpleMetricInput>> =
        simpleMetricInputsFromMetrics.groupBy {
            SimpleMetricAggregationConfiguration(
                timeDimensionName = it.aggTimeDimensionName,
                timeGrain = it.aggTimeDimensionGrain,
            )
        }
}
