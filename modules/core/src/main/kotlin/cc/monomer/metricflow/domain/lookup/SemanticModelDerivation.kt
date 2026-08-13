package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference

/**
 * Interface for an object that can be described as derived from a semantic model.
 *
 * Port of `metricflow_semantics/model/semantic_model_derivation.py::SemanticModelDerivation`.
 *
 * Implementations describe **which** semantic models they were derived from. The Python ABC
 * is recreated here as a Kotlin `interface`; the [VIRTUAL_SEMANTIC_MODEL_REFERENCE] constant
 * is hoisted to a top-level companion object on the interface so callers can reach it without
 * an instance.
 */
interface SemanticModelDerivation {

    /**
     * The semantic models that this was derived from.
     *
     * The returned sequence should be ordered and not contain duplicates.
     */
    val derivedFromSemanticModels: List<SemanticModelReference>

    companion object {
        /**
         * Sentinel used when an element does not directly correspond to something in the manifest.
         *
         * For example, when querying `metric_time` without any metrics. Python keeps this on the
         * ABC because it's queried statically; in Kotlin we put it on the companion object.
         */
        val VIRTUAL_SEMANTIC_MODEL_REFERENCE: SemanticModelReference =
            SemanticModelReference("__VIRTUAL__")
    }
}
