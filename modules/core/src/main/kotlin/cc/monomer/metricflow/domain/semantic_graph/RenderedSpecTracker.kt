package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.AnnotatedSpec

/**
 * Mutable object used to record the specs that were rendered in a where filter.
 *
 * Port of `metricflow_semantics/specs/rendered_spec_tracker.py::RenderedSpecTracker`.
 *
 * Useful for constructing a `WhereFilterSpec` because the renderer needs a
 * record of the specs referenced by the filter. Lives in `:domain:semantic_graph`
 * (and not `:domain:spec`) because it depends on [AnnotatedSpec], which lives
 * here.
 */
class RenderedSpecTracker {

    private val recorded: MutableList<AnnotatedSpec> = mutableListOf()

    /** Record a spec rendered in a where filter. */
    fun recordRenderedSpec(spec: AnnotatedSpec) {
        recorded.add(spec)
    }

    /** Specs recorded so far. Stable order = recording order. */
    val renderedSpecs: List<AnnotatedSpec> get() = recorded.toList()
}
