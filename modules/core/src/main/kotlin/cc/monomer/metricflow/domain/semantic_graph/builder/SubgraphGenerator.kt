package cc.monomer.metricflow.domain.semantic_graph.builder

import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge

/**
 * Generates a specific portion of the semantic graph.
 *
 * Port of `metricflow_semantics/semantic_graph/builder/subgraph_generator.py::SemanticSubgraphGenerator`.
 *
 * Each subgraph generator returns the **edges** it wants to add — the
 * [cc.monomer.metricflow.domain.semantic_graph.MutableSemanticGraph.addEdges]
 * call adds the endpoints automatically.
 */
abstract class SemanticSubgraphGenerator(protected val manifestObjectLookup: ManifestObjectLookup) {

    /** Mutate [edgeList] in-place with the edges this generator wants to add. */
    abstract fun addEdgesForManifest(edgeList: MutableList<SemanticGraphEdge>)

    /** Convenience: collect [addEdgesForManifest] into a new list. */
    fun generateEdges(): List<SemanticGraphEdge> {
        val edges = mutableListOf<SemanticGraphEdge>()
        addEdgesForManifest(edges)
        return edges
    }
}
