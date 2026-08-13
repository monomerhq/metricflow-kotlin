package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.semantic_graph.builder.CategoricalDimensionSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.ComplexMetricSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.EntityJoinSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.EntityKeySubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.SemanticSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.SimpleMetricSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.TimeDimensionSubgraphGenerator
import cc.monomer.metricflow.domain.semantic_graph.builder.TimeEntitySubgraphGenerator

/**
 * Builds a [SemanticGraph] from a [ManifestObjectLookup] by running each
 * subgraph generator in turn.
 *
 * Port of `metricflow_semantics/semantic_graph/builder/graph_builder.py::SemanticGraphBuilder`.
 *
 * The default generator order matches Python's `_ALL_SUBGRAPH_GENERATORS`.
 */
class SemanticGraphBuilder(private val manifestObjectLookup: ManifestObjectLookup) {

    /** The default ordered list of generators to run when [build] is called without arguments. */
    val defaultGenerators: List<SemanticSubgraphGenerator> = listOf(
        CategoricalDimensionSubgraphGenerator(manifestObjectLookup),
        EntityKeySubgraphGenerator(manifestObjectLookup),
        EntityJoinSubgraphGenerator(manifestObjectLookup),
        SimpleMetricSubgraphGenerator(manifestObjectLookup),
        TimeDimensionSubgraphGenerator(manifestObjectLookup),
        TimeEntitySubgraphGenerator(manifestObjectLookup),
        ComplexMetricSubgraphGenerator(manifestObjectLookup),
    )

    /** Build a [SemanticGraph] by running every generator in [generators]. */
    fun build(generators: List<SemanticSubgraphGenerator>): SemanticGraph {
        val graph = MutableSemanticGraph.create()
        for (generator in generators) {
            val edges = generator.generateEdges()
            graph.addEdges(edges)
        }
        return graph
    }

    /** Build using [defaultGenerators]. Convenience matching Python's default arg. */
    fun build(): SemanticGraph = build(defaultGenerators)
}
