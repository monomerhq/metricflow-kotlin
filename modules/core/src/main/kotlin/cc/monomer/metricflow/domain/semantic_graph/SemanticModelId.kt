package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.common.logging.MetricFlowPrettyFormattable
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Identifier for a semantic model used throughout the semantic-graph layer.
 *
 * Port of `metricflow_semantics/semantic_graph/model_id.py::SemanticModelId`.
 *
 * In Python this is a singleton replacement for [SemanticModelReference] kept
 * separate "because `SemanticModelReference` is defined in
 * `dbt-semantic-interfaces` and is difficult to change." The two are
 * isomorphic — [semanticModelReference] is the bridge to the manifest-side
 * reference.
 *
 * Kotlin uses a `@JvmInline value class` so the wrapper is zero-allocation at
 * runtime while still giving us a distinct type for the semantic-graph code
 * paths.
 */
@JvmInline
value class SemanticModelId(val modelName: String) :
    MetricFlowPrettyFormattable,
    Comparable<SemanticModelId> {

    /** Bridge to the manifest-side [SemanticModelReference]. */
    val semanticModelReference: SemanticModelReference get() = SemanticModelReference(modelName)

    override fun compareTo(other: SemanticModelId): Int = modelName.compareTo(other.modelName)

    override fun prettyFormat(): String = modelName

    override fun toString(): String = modelName

    companion object {
        /**
         * Construct (Python parity: a `get_instance` factory). Python relies on
         * a process-wide singleton table; Kotlin doesn't need one because the
         * inline value class collapses to its `String` representation at
         * runtime.
         */
        fun getInstance(modelName: String): SemanticModelId = SemanticModelId(modelName)
    }
}
