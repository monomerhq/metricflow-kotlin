package cc.monomer.metricflow.domain.manifest.transformation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest

/**
 * A single rule in the manifest-transformation pipeline.
 *
 * Port of `metricflow_semantic_interfaces/transformations/transform_rule.py::SemanticManifestTransformRule`.
 *
 * A rule consumes a `SemanticManifest` and returns a (logically) new one with its
 * canonicalisation applied. Implementations must be **pure functions**: no side effects beyond
 * constructing the returned manifest via `.copy(...)`. Rules compose via
 * [SemanticManifestTransformer]; ordering matters (see [SemanticManifestTransformRuleSet]).
 */
fun interface SemanticManifestTransformRule {
    /** Return a new [SemanticManifest] that is the input transformed by this rule. */
    fun transformModel(semanticManifest: SemanticManifest): SemanticManifest
}
