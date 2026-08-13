package cc.monomer.metricflow.domain.query.group_by

import cc.monomer.metricflow.domain.query.group_by.resolution_dag.MetricFlowQueryResolutionPath

/**
 * Marker for types that carry a [MetricFlowQueryResolutionPath] and need to
 * be relocated (prefixed) when traversed inside a parent resolver.
 *
 * Port of `metricflow_semantics.query.group_by_item.path_prefixable.PathPrefixable`.
 *
 * During a recursive walk of the resolution DAG, the path from the start
 * node to the current node is threaded as a prefix. Issues / lookups that
 * produced their own *relative* path can re-anchor themselves by calling
 * [withPathPrefix] with the prefix path.
 *
 * The return type is `Self` in Python; Kotlin emulates this with a
 * recursive bound on [T] so concrete implementations return their own
 * type without an `as` cast.
 */
interface PathPrefixable<T : PathPrefixable<T>> {
    /** Return a copy of this, prepending [pathPrefix] to the contained path. */
    fun withPathPrefix(pathPrefix: MetricFlowQueryResolutionPath): T
}
