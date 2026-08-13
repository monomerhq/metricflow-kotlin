package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.SemanticGraphGroupByItemSetResolver
import cc.monomer.metricflow.domain.semantic_graph.metric_input.SimpleMetricInput
import cc.monomer.metricflow.domain.semantic_graph.pathfinder.MetricFlowPathfinder

/**
 * Composition root for the semantic-graph layer.
 *
 * The Python `SemanticManifestLookup` (W7a's
 * [cc.monomer.metricflow.domain.lookup.SemanticManifestLookup]) wires
 * in the [ManifestObjectLookup], the [SemanticGraphBuilder], a
 * [MetricFlowPathfinder], and a [SemanticGraphGroupByItemSetResolver]. The
 * W7a port intentionally left those four wires unconnected because they live
 * here in `:domain:semantic_graph`.
 *
 * This class **composes** the W7a [SemanticManifestLookup] (it does not
 * subclass — `:domain:lookup` is read-only from our perspective) and adds the
 * semantic-graph-side bindings. The W10 engine facade depends on this type as
 * the single composition root for "everything the manifest exposes".
 *
 * Initialization cost:
 *
 * 1. [manifestObjectLookup] — eager indexes (a few millis on a corpus manifest).
 * 2. [semanticGraph] — builds the full graph by running every subgraph generator.
 * 3. [groupByItemSetResolver] — bootstraps a [SemanticGraphGroupByItemSetResolver]
 *    over the graph.
 *
 * Items 2 and 3 are deferred to first access by `lazy`, so callers that only
 * use [semanticManifestLookup] / [manifestObjectLookup] do not pay the
 * construction cost.
 */
class SemanticManifestGraphLookup(val semanticManifestLookup: SemanticManifestLookup) {

    /** The W7a lookup, exposed as a property for callers that don't need the graph. */
    val manifestObjectLookup: ManifestObjectLookup =
        ManifestObjectLookup(semanticManifestLookup.semanticManifest)

    /** The fully-built semantic graph. */
    val semanticGraph: SemanticGraph by lazy { SemanticGraphBuilder(manifestObjectLookup).build() }

    /** A pathfinder over [semanticGraph]. */
    val pathfinder: MetricFlowPathfinder by lazy { MetricFlowPathfinder() }

    /** Resolver for the group-by items available against [semanticGraph]. */
    val groupByItemSetResolver: SemanticGraphGroupByItemSetResolver by lazy {
        SemanticGraphGroupByItemSetResolver(
            semanticGraph = semanticGraph,
            manifestObjectLookup = manifestObjectLookup,
            pathfinder = pathfinder,
        )
    }

    /** Convenience accessor: simple-metric name → [SimpleMetricInput]. */
    val simpleMetricNameToInput: Map<String, SimpleMetricInput>
        get() = manifestObjectLookup.simpleMetricNameToInput
}
