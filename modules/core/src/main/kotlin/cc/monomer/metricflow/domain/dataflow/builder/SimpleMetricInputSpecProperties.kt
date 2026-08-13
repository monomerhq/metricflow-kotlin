package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInput
import cc.monomer.metricflow.domain.spec.NonAdditiveDimensionSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec

/**
 * Common properties extracted from a uniform group of [SimpleMetricInput]s.
 *
 * Port of
 * `metricflow.dataflow.builder.simple_metric_input_spec_properties.SimpleMetricInputSpecProperties`.
 *
 * The builder calls [createFromSimpleMetricInputs] with a sequence of `SimpleMetricInput`s that
 * are expected to share a grouping key (same semantic model, agg-time-dimension, grain, and
 * non-additive-dimension parameters). If the inputs disagree, the factory fails.
 */
data class SimpleMetricInputSpecProperties(
    val simpleMetricInputSpecs: List<SimpleMetricInputSpec>,
    val semanticModelName: String,
    val aggTimeDimension: TimeDimensionReference,
    val aggTimeDimensionGrain: TimeGranularity,
    val nonAdditiveDimensionSpec: NonAdditiveDimensionSpec?,
) {

    companion object {

        /**
         * Group the inputs by their `(model_id, agg_time_dim_name, agg_time_dim_grain,
         * non_additive_dim_spec)` key — they must all share one.
         */
        fun createFromSimpleMetricInputs(
            simpleMetricInputs: List<SimpleMetricInput>,
        ): SimpleMetricInputSpecProperties {
            require(simpleMetricInputs.isNotEmpty()) { "No simple-metric inputs provided" }

            val keyToInputs = LinkedHashMap<SimpleMetricInputGroupingKey, MutableList<SimpleMetricInput>>()
            for (input in simpleMetricInputs) {
                val key = SimpleMetricInputGroupingKey.createFromSimpleMetricInput(input)
                keyToInputs.getOrPut(key) { mutableListOf() }.add(input)
            }
            require(keyToInputs.size == 1) {
                "The given simple-metric inputs do not have the same grouping key (keyToInputs=$keyToInputs)"
            }
            val commonKey = keyToInputs.keys.first()
            return SimpleMetricInputSpecProperties(
                simpleMetricInputSpecs = simpleMetricInputs.map {
                    SimpleMetricInputSpec(elementName = it.name, fillNullsWith = null)
                },
                semanticModelName = commonKey.modelId.modelName,
                aggTimeDimension = TimeDimensionReference(commonKey.aggTimeDimensionName),
                aggTimeDimensionGrain = commonKey.aggTimeDimensionGrain,
                nonAdditiveDimensionSpec = commonKey.nonAdditiveDimensionSpec,
            )
        }
    }
}

/**
 * Composite key for grouping simple-metric inputs that share aggregation parameters.
 * Port of the file-private `_SimpleMetricInputGroupingKey`.
 */
internal data class SimpleMetricInputGroupingKey(
    val modelId: SemanticModelId,
    val aggTimeDimensionName: String,
    val aggTimeDimensionGrain: TimeGranularity,
    val nonAdditiveDimensionSpec: NonAdditiveDimensionSpec?,
) {
    companion object {
        fun createFromSimpleMetricInput(input: SimpleMetricInput): SimpleMetricInputGroupingKey {
            val nonAdditive = input.nonAdditiveDimension?.let {
                NonAdditiveDimensionSpec(
                    name = it.name,
                    windowChoice = it.windowChoice,
                    windowGroupings = it.windowGroupings,
                )
            }
            return SimpleMetricInputGroupingKey(
                modelId = input.modelId,
                aggTimeDimensionName = input.aggTimeDimensionName,
                aggTimeDimensionGrain = input.aggTimeDimensionGrain,
                nonAdditiveDimensionSpec = nonAdditive,
            )
        }
    }
}
