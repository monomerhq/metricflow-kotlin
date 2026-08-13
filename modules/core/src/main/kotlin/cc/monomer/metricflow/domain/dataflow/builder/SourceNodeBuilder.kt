package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.domain.dataflow.DataflowPlanNode
import cc.monomer.metricflow.domain.dataflow.dataset.SemanticModelDataSet
import cc.monomer.metricflow.domain.dataflow.nodes.MetricTimeDimensionTransformNode
import cc.monomer.metricflow.domain.dataflow.nodes.ReadSqlSourceNode
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import cc.monomer.metricflow.domain.spec.ColumnAssociationResolver

/**
 * Builds a [SourceNodeSet] from per-semantic-model data sets.
 *
 * Port of `metricflow.dataflow.builder.source_node.SourceNodeBuilder`.
 *
 * **Wiring caveat.** The Python class owns a `SemanticModelToDataSetConverter` and uses it to
 * (a) produce a time-spine dataset, and (b) wrap each semantic-model's data into a
 * [ReadSqlSourceNode]. The Kotlin port of `convert_semantic_model.py` (594 LOC, deep
 * dependencies on SQL expressions and column-association resolution) is deferred until the
 * `:application:engine` wave that needs it; until then, this builder accepts pre-converted
 * [SemanticModelDataSet]s and pre-built time-spine read nodes from its caller.
 *
 * The query-parser dependency that Python's `SourceNodeBuilder` uses to satisfy group-by-metric
 * source-node queries is also deferred — `buildSourceNodeInputsForGroupByMetric` is omitted
 * here because the Python implementation is "just a wrapper around the query parser method"
 * and lives outside this wave's scope.
 */
class SourceNodeBuilder(
    private val columnAssociationResolver: ColumnAssociationResolver,
    semanticManifestGraphLookup: SemanticManifestGraphLookup,
    /**
     * Pre-built time-spine read nodes by base granularity. Caller supplies these because the
     * full SQL conversion of a `TimeSpineSource` is part of the converter deferral above.
     */
    private val timeSpineReadNodesIn: Map<TimeGranularity, ReadSqlSourceNode>,
    /**
     * Pre-built time-spine metric-time transform nodes by base granularity. One per entry in
     * [timeSpineReadNodesIn]. Caller wires these.
     */
    private val timeSpineMetricTimeNodesIn: Map<TimeGranularity, MetricTimeDimensionTransformNode>,
) {

    private val semanticManifestLookup: SemanticManifestLookup =
        semanticManifestGraphLookup.semanticManifestLookup
    private val manifestObjectLookup = semanticManifestGraphLookup.manifestObjectLookup

    /**
     * Build a [SourceNodeSet] from semantic-model datasets. Port of
     * `SourceNodeBuilder.create_from_data_sets`.
     *
     * For each input dataset:
     *
     * - emit a [ReadSqlSourceNode] (used as a candidate join node — appears in
     *   `sourceNodesForGroupByItemQueries`); and
     * - if the model has any simple-metric inputs, emit one
     *   [MetricTimeDimensionTransformNode] per distinct `aggregation_time_dimension`. Each
     *   such transform is used as a metric-query source-node candidate.
     * - if the model has no simple-metric inputs (a "dimension source"), the bare read node is
     *   added to the metric-query source list directly.
     */
    fun createFromDataSets(dataSets: List<SemanticModelDataSet>): SourceNodeSet {
        val groupByItemSourceNodes = mutableListOf<DataflowPlanNode>()
        val sourceNodesForMetricQueries = mutableListOf<DataflowPlanNode>()

        val modelReferenceToLookup = manifestObjectLookup.simpleMetricModelLookups.associateBy {
            it.semanticModel.reference
        }

        for (dataSet in dataSets) {
            val readNode = ReadSqlSourceNode(dataSet)
            groupByItemSourceNodes.add(readNode)

            val modelReference = dataSet.semanticModelReference
            val simpleMetricLookup = modelReferenceToLookup[modelReference]
            if (simpleMetricLookup == null) {
                // Dimension source — no simple-metric inputs, no metric-time transforms.
                sourceNodesForMetricQueries.add(readNode)
            } else {
                for (timeDimensionName in simpleMetricLookup.aggregationTimeDimensionNameToSimpleMetricInputs.keys) {
                    val transform = MetricTimeDimensionTransformNode(
                        parentNode = readNode,
                        aggregationTimeDimensionReference = TimeDimensionReference(timeDimensionName),
                    )
                    sourceNodesForMetricQueries.add(transform)
                }
            }
        }

        return SourceNodeSet(
            sourceNodesForMetricQueries = sourceNodesForMetricQueries.toList(),
            sourceNodesForGroupByItemQueries = groupByItemSourceNodes.toList(),
            timeSpineReadNodes = timeSpineReadNodesIn,
            timeSpineMetricTimeNodes = timeSpineMetricTimeNodesIn,
        )
    }
}
