package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.optimizer.DataflowPlanOptimization
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableSpecSet
import cc.monomer.metricflow.domain.spec.MetricSpec
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec

/**
 * Options that affect how a dataflow plan is built.
 *
 * Port of `metricflow.dataflow.builder.builder_cache.DataflowPlanOptionSet`.
 */
data class DataflowPlanOptionSet(
    val optimizations: Set<DataflowPlanOptimization>,
    val outputGroupByMetricInstances: Boolean,
) {
    /** Return a copy with [outputGroupByMetricInstances] replaced. */
    fun withOutputGroupByMetricInstances(value: Boolean): DataflowPlanOptionSet =
        copy(outputGroupByMetricInstances = value)
}

/**
 * Parameters for [cc.monomer.metricflow.domain.dataflow.builder.DataflowPlanBuilder]'s
 * `_find_source_node_recipe()` cache. Port of `FindSourceNodeRecipeInput`.
 *
 * `PredicatePushdownState` is not yet ported (its W9c neighbour `node_processor` carries it).
 * We mirror Python's struct as `predicatePushdownState: Any?` for now so consumers can store
 * the W9c state opaquely; this field can be tightened to a real type once W9c lands.
 */
data class FindSourceNodeRecipeInput(
    val simpleMetricInputSpecs: List<SimpleMetricInputSpec>?,
    val linkableSpecSet: LinkableSpecSet,
    val predicatePushdownState: Any?,
    val optimizations: Set<DataflowPlanOptimization>,
)

/** Result for `_find_source_node_recipe()`. Port of `FindSourceNodeRecipeResult`. */
data class FindSourceNodeRecipeResult(val sourceNodeRecipe: SourceNodeRecipe?)

/**
 * Parameters for `_build_any_metric_output_node()`. Port of `BuildAnyMetricOutputNodeInput`.
 */
data class BuildAnyMetricOutputNodeInput(
    val metricQueryDescriptor: MetricQueryDescriptor,
    val optimizations: Set<DataflowPlanOptimization>,
)

/**
 * Describes a metric query for use as a cache key.
 *
 * Port of `metricflow.dataflow.builder.builder_cache.MetricQueryDescriptor`.
 */
data class MetricQueryDescriptor(
    val computedMetricSpecs: List<MetricSpec>,
    val passthroughMetricSpecs: List<MetricSpec>,
    val groupByItemSpecs: List<LinkableInstanceSpec>,
    val predicatePushdownState: Any?,
) {
    companion object {
        fun create(
            computedMetricSpecs: Iterable<MetricSpec>,
            passthroughMetricSpecs: Iterable<MetricSpec>,
            groupByItemSpecs: Iterable<LinkableInstanceSpec>,
            predicatePushdownState: Any?,
        ): MetricQueryDescriptor = MetricQueryDescriptor(
            computedMetricSpecs = LinkedHashSet(computedMetricSpecs.toList()).toList(),
            passthroughMetricSpecs = LinkedHashSet(passthroughMetricSpecs.toList()).toList(),
            groupByItemSpecs = LinkedHashSet(groupByItemSpecs.toList()).toList(),
            predicatePushdownState = predicatePushdownState,
        )
    }
}

/**
 * Cache for the internal recursion in [DataflowPlanBuilder]. Port of `DataflowPlanBuilderCache`.
 *
 * Implemented with simple [HashMap]s rather than Python's `LruCache` — the planner builds a
 * single plan per call so eviction is unnecessary; the cache is dropped after each `buildPlan`.
 */
class DataflowPlanBuilderCache {
    private val findSourceNodeRecipeCache: MutableMap<FindSourceNodeRecipeInput, FindSourceNodeRecipeResult> =
        HashMap()
    private val buildAnyMetricOutputNodeCache: MutableMap<BuildAnyMetricOutputNodeInput, DataflowPlanNode> =
        HashMap()

    fun getFindSourceNodeRecipeResult(key: FindSourceNodeRecipeInput): FindSourceNodeRecipeResult? =
        findSourceNodeRecipeCache[key]

    fun setFindSourceNodeRecipeResult(
        key: FindSourceNodeRecipeInput,
        result: FindSourceNodeRecipeResult,
    ) {
        findSourceNodeRecipeCache[key] = result
    }

    fun getBuildAnyMetricOutputNodeResult(key: BuildAnyMetricOutputNodeInput): DataflowPlanNode? =
        buildAnyMetricOutputNodeCache[key]

    fun setBuildAnyMetricOutputNodeResult(
        key: BuildAnyMetricOutputNodeInput,
        node: DataflowPlanNode,
    ) {
        buildAnyMetricOutputNodeCache[key] = node
    }
}
