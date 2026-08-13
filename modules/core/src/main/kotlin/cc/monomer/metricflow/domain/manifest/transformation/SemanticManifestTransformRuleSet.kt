package cc.monomer.metricflow.domain.manifest.transformation

/**
 * Two ordered phases of transformation rules.
 *
 * Port of `metricflow_semantic_interfaces/transformations/rule_set.py::SemanticManifestTransformRuleSet`.
 *
 * The Python protocol exposes [primaryRules] and [secondaryRules] separately and also yields
 * them in an `all_rules` 2-D sequence. We mirror that 2-phase shape: [primaryRules] runs
 * first (top-level normalisation such as lower-casing names), then [secondaryRules] runs
 * (everything else — measures legacy fixes, proxy metrics, derived-metric input wiring, etc.).
 *
 * Ordering inside each list is significant. In particular, in the canonical rule set
 * [DefaultTransformRuleSet]:
 *  - `CreateProxyMeasureRule` must run before `AddInputMetricMeasuresRule` so derived metrics
 *    see the auto-created proxies in their input list.
 *  - `SetCumulativeTypeParamsRule` must run before `RemovePluralFromWindowGranularityRule`
 *    because the latter walks the new `cumulative_type_params.window` field the former populates.
 */
data class SemanticManifestTransformRuleSet(
    /** Rules executed first — top-level normalisation. */
    val primaryRules: List<SemanticManifestTransformRule>,
    /** Rules executed after [primaryRules] — measures, metrics, fixups. */
    val secondaryRules: List<SemanticManifestTransformRule>,
) {
    /** All rule phases in order. Mirrors Python's `all_rules` 2-D sequence. */
    val allRules: List<List<SemanticManifestTransformRule>>
        get() = listOf(primaryRules, secondaryRules)
}
