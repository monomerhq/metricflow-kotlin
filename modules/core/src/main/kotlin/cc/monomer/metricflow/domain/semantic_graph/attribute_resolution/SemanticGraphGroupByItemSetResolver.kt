package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.semantic_graph.ComplexMetricNode
import cc.monomer.metricflow.domain.semantic_graph.LocalModelNode
import cc.monomer.metricflow.domain.semantic_graph.ManifestObjectLookup
import cc.monomer.metricflow.domain.semantic_graph.MetricTimeNode
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraph
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphEdge
import cc.monomer.metricflow.domain.semantic_graph.SemanticGraphNode
import cc.monomer.metricflow.domain.semantic_graph.SemanticModelId
import cc.monomer.metricflow.domain.semantic_graph.SimpleMetricNode
import cc.monomer.metricflow.domain.semantic_graph.edge.DenyVisibleAttributesLabel
import cc.monomer.metricflow.domain.semantic_graph.node.LocalModelLabel
import cc.monomer.metricflow.domain.semantic_graph.node.MetricTimeLabel
import cc.monomer.metricflow.domain.semantic_graph.node.SimpleMetricLabel
import cc.monomer.metricflow.domain.semantic_graph.pathfinder.MetricFlowPathfinder
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameDescriptor
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameTrie

/**
 * Resolves the set of group-by items available for a metric by walking the
 * semantic graph with a DFS path enumerator.
 *
 * Port of `metricflow_semantics/semantic_graph/attribute_resolution/sg_linkable_spec_resolver.py`
 * + the simple-trie resolver in
 * `metricflow_semantics/semantic_graph/trie_resolver/simple_resolver.py`.
 *
 * ## Algorithm
 *
 * Mirrors Python's two-stage approach:
 *
 * 1. **Locate intersection-source nodes.** Walk the graph from the supplied
 *    source nodes (metric / local-model / metric-time) to identify the
 *    "intersection sources" — the local-model nodes and metric-time nodes that
 *    every supplied source can reach. For a single metric, the intersection
 *    sources for a simple metric are its `LocalModelNode` + the `MetricTimeNode`;
 *    for a complex metric, they are the union of the intersection sources of
 *    every input metric.
 * 2. **DFS each intersection source.** For each intersection source, run a
 *    [PathEnumerator] DFS to every attribute node, accumulating an
 *    [AttributeRecipe]. The DFS applies the four weight rules from Python's
 *    `AttributeRecipeWriterWeightFunction` (repeated-dunder, repeated-model,
 *    `MAX_JOIN_HOPS`, invalid-entity-link-count).
 * 3. **Combine.** Tries from the same simple-metric source are combined via
 *    [DunderNameTrie.unionExcludeCommon] (Python's `union_exclude_common`).
 *    Tries from different metrics are combined via
 *    [DunderNameTrie.intersectionMergeCommon].
 * 4. **Materialise.** Convert the resulting trie into a [GroupByItemSet] via
 *    [AnnotatedSpec.createFromIndexedDunderName].
 *
 * ## Deferred Python pieces
 *
 * - **Group-by-metric resolution** (Python `GroupByMetricTrieResolver`,
 *   ~500 LOC) — group-by metrics rely on the entity-key trie and a separate
 *   resolver. This W11 port focuses on `LinkableElementType.{DIMENSION,
 *   TIME_DIMENSION, ENTITY}`. `METRIC`-typed group-by items will arrive in a
 *   later wave; for the corpus they only appear in a handful of cases.
 * - **Cumulative-metric `DENY_DATE_PART` propagation** — Python checks
 *   `labels_collected_during_traversal` for `DenyDatePartLabel` and applies a
 *   filter retroactively. We don't yet (the corpus does not exercise it on the
 *   simple-manifest paths that W11 targets).
 * - **Result caching.** Python's `ResultCache` is omitted; the corpus volume is
 *   small enough that recomputation is cheap.
 *
 * Where this resolver still falls short, the W7c BFS fallback was the previous
 * implementation; we keep the public interface stable so future waves can
 * refine the body without breaking callers.
 */
class SemanticGraphGroupByItemSetResolver(
    private val semanticGraph: SemanticGraph,
    private val manifestObjectLookup: ManifestObjectLookup,
    private val pathfinder: MetricFlowPathfinder,
) : GroupByItemSetResolver {

    private val pathEnumerator = PathEnumerator(semanticGraph)

    /**
     * The "virtual" model used when an attribute has no real model context
     * (e.g. raw `metric_time`). Mirrors Python's `_virtual_semantic_model_ids`
     * pinning to `SemanticModelDerivation.VIRTUAL_SEMANTIC_MODEL_REFERENCE`.
     */
    private val virtualModelIds: List<SemanticModelId> =
        listOf(SemanticModelId.getInstance("virtual"))

    override fun resolveAvailableItemsForMetric(metricReference: MetricReference): GroupByItemSet {
        val sourceNode = findMetricNode(metricReference.elementName)
            ?: return GroupByItemSet.EMPTY
        val trie = resolveTrieForSourceNodes(listOf(sourceNode))
        return GroupByItemSet.createFromTrie(trie)
    }

    override fun resolveAvailableItemsForNoMetricsInQuery(): GroupByItemSet {
        // Python's distinct-values path unions the simple-trie of every local
        // model (with `max_path_model_count=1`) plus the metric-time trie. We
        // emulate the union-without-metric-time-grain-filter — the metric-time
        // filtering is exercised by the explain path, which is out of scope
        // for W11.
        val localModels = semanticGraph.nodesWithLabels(LocalModelLabel).toList()
        if (localModels.isEmpty()) return GroupByItemSet.EMPTY
        val tries = mutableListOf<DunderNameTrie>()
        for (local in localModels) {
            tries.add(
                resolveTrieFromInitialPath(
                    startNode = local,
                    initialRecipe = AttributeRecipe.create(local.recipeStepToAppend),
                    maxPathModelCount = 1,
                ),
            )
        }
        tries.add(
            resolveTrieFromInitialPath(
                startNode = MetricTimeNode,
                initialRecipe = AttributeRecipe.create(MetricTimeNode.recipeStepToAppend),
                maxPathModelCount = null,
            ),
        )
        val unioned = DunderNameTrie.unionMergeCommon(tries)
        return GroupByItemSet.createFromTrie(unioned)
    }

    private fun findMetricNode(metricName: String): SemanticGraphNode? =
        semanticGraph.nodes.firstOrNull { node ->
            (node is SimpleMetricNode && node.metricName == metricName) ||
                (node is ComplexMetricNode && node.metricName == metricName)
        }

    /**
     * Resolve the dunder-name trie for the given source nodes by intersecting
     * the tries produced by each node.
     *
     * Mirrors Python's `SimpleTrieResolver._resolve_trie_for_source_nodes`.
     */
    private fun resolveTrieForSourceNodes(sourceNodes: List<SemanticGraphNode>): DunderNameTrie {
        val intersectionSources = collectIntersectionSources(sourceNodes)
        if (intersectionSources.isEmpty()) return DunderNameTrie()
        val perSourceTries = intersectionSources.map { resolveTrieFromNode(it) }
        return DunderNameTrie.intersectionMergeCommon(perSourceTries)
    }

    /**
     * Walk forward from each source node and collect the local-model /
     * simple-metric / metric-time nodes that every source can reach. Those are
     * the "intersection sources" Python feeds into the per-node resolution.
     */
    private fun collectIntersectionSources(sourceNodes: List<SemanticGraphNode>): List<SemanticGraphNode> {
        val collected = LinkedHashSet<SemanticGraphNode>()
        for (source in sourceNodes) {
            collected.addAll(walkToIntersectionSources(source))
        }
        return collected.toList()
    }

    /**
     * BFS-from a single source node to all reachable local-model / simple-metric
     * / metric-time descendants, treating the metric / metric-time / local-model
     * nodes as the only allowed entity nodes (mirrors Python's
     * `node_allow_set`).
     */
    private fun walkToIntersectionSources(source: SemanticGraphNode): List<SemanticGraphNode> {
        // For a SimpleMetricNode the intersection sources are the node itself
        // (because the per-node resolver splits the metric into its two
        // branches). For a ComplexMetricNode we recurse on its successors.
        // For a LocalModelNode or MetricTimeNode the source is itself the
        // entry point.
        return when {
            source is LocalModelNode -> listOf(source)
            source is MetricTimeNode -> listOf(source)
            source is SimpleMetricNode -> listOf(source)
            source is ComplexMetricNode -> {
                val out = LinkedHashSet<SemanticGraphNode>()
                val seen = mutableSetOf<SemanticGraphNode>()
                val frontier = ArrayDeque<SemanticGraphNode>()
                frontier.add(source)
                seen.add(source)
                while (frontier.isNotEmpty()) {
                    val cur = frontier.removeFirst()
                    for (edge in semanticGraph.edgesWithTailNode(cur)) {
                        // Conversion-input edges carry DenyVisibleAttributesLabel —
                        // Python skips them when collecting descendants (see
                        // `_resolve_trie_for_source_nodes`'s `deny_labels=
                        // {DenyVisibleAttributesLabel}`). The conversion-input
                        // metric does not contribute group-by items to the
                        // outer metric's set.
                        if (edge.labels.contains(DenyVisibleAttributesLabel)) continue
                        val head = edge.headNode
                        if (head in seen) continue
                        seen.add(head)
                        when (head) {
                            is SimpleMetricNode -> out.add(head)
                            is LocalModelNode -> out.add(head)
                            is MetricTimeNode -> out.add(head)
                            is ComplexMetricNode -> frontier.add(head)
                            else -> Unit
                        }
                    }
                }
                out.toList()
            }
            else -> emptyList()
        }
    }

    /**
     * Resolve the trie for one intersection-source node, dispatching on the
     * node's label kind. Mirrors `SimpleTrieResolver._resolve_trie_from_node`.
     */
    private fun resolveTrieFromNode(sourceNode: SemanticGraphNode): DunderNameTrie {
        return when {
            sourceNode.labels.contains(LocalModelLabel) || sourceNode.labels.contains(MetricTimeLabel) ->
                resolveTrieFromInitialPath(
                    startNode = sourceNode,
                    initialRecipe = AttributeRecipe.create(sourceNode.recipeStepToAppend),
                    maxPathModelCount = null,
                )
            sourceNode.labels.contains(SimpleMetricLabel) -> resolveTrieForSimpleMetric(sourceNode)
            else -> DunderNameTrie()
        }
    }

    /**
     * For a simple metric, find its `LocalModelNode` and `MetricTimeNode`
     * successors and build a trie from each, then `unionExcludeCommon` the two
     * results. Mirrors the simple-metric branch in
     * `SimpleTrieResolver._resolve_trie_from_node`.
     */
    private fun resolveTrieForSimpleMetric(simpleMetricNode: SemanticGraphNode): DunderNameTrie {
        var localModelEdge: SemanticGraphEdge? = null
        var metricTimeEdge: SemanticGraphEdge? = null
        for (edge in semanticGraph.edgesWithTailNode(simpleMetricNode)) {
            if (edge.headNode.labels.contains(LocalModelLabel)) localModelEdge = edge
            if (edge.headNode.labels.contains(MetricTimeLabel)) metricTimeEdge = edge
        }
        if (localModelEdge == null || metricTimeEdge == null) return DunderNameTrie()

        val localTrie = resolveTrieFromInitialPath(
            startNode = localModelEdge.headNode,
            initialRecipe = AttributeRecipe.create(localModelEdge.headNode.recipeStepToAppend),
            maxPathModelCount = null,
        )
        val metricTimeStartRecipe = AttributeRecipe.EMPTY
            .appendStep(metricTimeEdge.recipeStepToAppend)
            .appendStep(metricTimeEdge.headNode.recipeStepToAppend)
        val metricTimeTrie = resolveTrieFromInitialPath(
            startNode = metricTimeEdge.headNode,
            initialRecipe = metricTimeStartRecipe,
            maxPathModelCount = null,
        )
        return DunderNameTrie.unionExcludeCommon(listOf(localTrie, metricTimeTrie))
    }

    /**
     * Enumerate every valid DFS path that starts at [startNode] with the given
     * recipe and ends at an attribute node. Materialise each ending recipe as
     * an entry in the returned trie.
     *
     * The `maxPathModelCount` parameter mirrors Python's `max_path_model_count`
     * — used by the distinct-values query to limit `local_model` tries to a
     * single semantic model.
     */
    private fun resolveTrieFromInitialPath(
        startNode: SemanticGraphNode,
        initialRecipe: AttributeRecipe,
        maxPathModelCount: Int?,
    ): DunderNameTrie {
        val paths = pathEnumerator.enumeratePathsToAttributes(startNode, initialRecipe)
        val items = mutableListOf<Pair<IndexedDunderName, DunderNameDescriptor>>()
        for (path in paths) {
            val recipe = path.recipe
            val elementType = recipe.elementType ?: continue
            if (maxPathModelCount != null && recipe.joinedModelIds.size > maxPathModelCount) continue
            val resolvedProperties = try {
                recipe.resolveCompleteProperties().toList()
            } catch (_: IllegalStateException) {
                // Skip incomplete/invalid recipes (e.g. a path that didn't
                // accumulate METRIC_TIME but has no joined model).
                continue
            }
            val originModelIds = if (recipe.joinedModelIds.isNotEmpty()) {
                listOf(recipe.joinedModelIds.last())
            } else {
                virtualModelIds
            }
            val derivedFrom = if (recipe.joinedModelIds.isNotEmpty()) {
                recipe.joinedModelIds
            } else {
                virtualModelIds
            }
            items.add(
                recipe.indexedDunderName to DunderNameDescriptor(
                    elementType = elementType,
                    timeGrain = recipe.recipeTimeGrain,
                    datePart = recipe.recipeDatePart,
                    elementProperties = resolvedProperties,
                    originModelIds = originModelIds,
                    derivedFromModelIds = derivedFrom,
                    entityKeyQueriesForGroupByMetric = emptyList(),
                ),
            )
        }
        val trie = DunderNameTrie()
        trie.addNameItems(items)
        return trie
    }

}
