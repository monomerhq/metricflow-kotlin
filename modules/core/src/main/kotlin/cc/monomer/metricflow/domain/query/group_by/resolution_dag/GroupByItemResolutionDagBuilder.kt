package cc.monomer.metricflow.domain.query.group_by.resolution_dag

import cc.monomer.metricflow.domain.lookup.MetricLookup
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.filter.WhereFilterIntersection
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.ComplexMetricGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.GroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.NoMetricsGroupByItemSourceNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.QueryGroupByItemResolutionNode
import cc.monomer.metricflow.domain.query.group_by.resolution_dag.node.SimpleMetricGroupByItemSourceNode

/**
 * Build a [GroupByItemResolutionDag] from a query's `(metrics, where-filter)`
 * inputs.
 *
 * Port of
 * `metricflow_semantics.query.group_by_item.resolution_dag.dag_builder.GroupByItemResolutionDagBuilder`.
 *
 * The builder recurses through each metric's inputs (the derived-metric
 * structure) to produce the source / complex-metric / query node chain.
 * The walk reuses the manifest's [MetricLookup.metricInputs] helper to
 * traverse the derived-metric tree.
 */
class GroupByItemResolutionDagBuilder(
    private val manifestLookup: SemanticManifestLookup,
) {

    /**
     * Build the resolution DAG for a query.
     *
     * @param metricReferences the metrics in the query.
     * @param whereFilterIntersection the filters in the query, or `null` if
     *     no filters were supplied (`null` is replaced with the empty
     *     intersection — same as Python).
     */
    fun build(
        metricReferences: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection?,
    ): GroupByItemResolutionDag = GroupByItemResolutionDag(
        sinkNode = buildDagComponentForQuery(
            metricReferences = metricReferences,
            whereFilterIntersection = whereFilterIntersection ?: WhereFilterIntersection(emptyList()),
        ),
    )

    private fun buildDagComponentForQuery(
        metricReferences: List<MetricReference>,
        whereFilterIntersection: WhereFilterIntersection,
    ): QueryGroupByItemResolutionNode {
        val parents: List<GroupByItemResolutionNode> = if (metricReferences.isEmpty()) {
            listOf(NoMetricsGroupByItemSourceNode.create())
        } else {
            metricReferences.map { metricReference ->
                buildDagComponentForMetric(
                    metricReference = metricReference,
                    metricInputLocation = null,
                )
            }
        }
        return QueryGroupByItemResolutionNode.create(
            parentNodes = parents,
            metricsInQuery = metricReferences,
            whereFilterIntersection = whereFilterIntersection,
        )
    }

    private fun buildDagComponentForMetric(
        metricReference: MetricReference,
        metricInputLocation: InputMetricDefinitionLocation?,
    ): GroupByItemResolutionNode {
        val metric = manifestLookup.metricLookup.getMetric(metricReference)
        val metricInputs = MetricLookup.metricInputs(metric, includeConversionMetricInput = false)

        if (metricInputs.isEmpty()) {
            return SimpleMetricGroupByItemSourceNode.create(
                simpleMetricReference = metricReference,
                metricInputLocation = metricInputLocation,
            )
        }

        val parents = metricInputs.mapIndexed { i, metricInput ->
            buildDagComponentForMetric(
                metricReference = MetricReference(metricInput.name),
                metricInputLocation = InputMetricDefinitionLocation(
                    derivedMetricReference = metricReference,
                    inputMetricListIndex = i,
                ),
            )
        }

        return ComplexMetricGroupByItemResolutionNode.create(
            metricReference = metricReference,
            metricInputLocation = metricInputLocation,
            parentNodes = parents,
        )
    }
}
