package cc.monomer.metricflow.domain.manifest.transformation

import cc.monomer.metricflow.domain.manifest.model.SemanticManifest

/**
 * Orchestrator that applies every rule in a [SemanticManifestTransformRuleSet] to a manifest.
 *
 * Port of
 * `metricflow_semantic_interfaces/transformations/semantic_manifest_transformer.py::PydanticSemanticManifestTransformer`.
 *
 * Iterates the rule set in declared order: every rule in `primaryRules` runs (in order),
 * then every rule in `secondaryRules` runs (in order). Each rule is invoked on the output of
 * the previous one, producing a single final canonicalised manifest.
 *
 * The Python `transform()` accepts an optional `ordered_rule_sequences` argument, defaulting to
 * `PydanticSemanticManifestTransformRuleSet().all_rules`. We mirror that with a Kotlin overload:
 * the canonical entry point [transform] without a rule set delegates to [DefaultTransformRuleSet].
 *
 * The no-arg [transform] overload delegates to [DefaultTransformRuleSet]; the two-arg overload
 * accepts a custom [SemanticManifestTransformRuleSet]. Two explicit overloads (no default value)
 * keep both call shapes equally idiomatic and honor backend-conventions "Explicit Code" ban on
 * default parameter values.
 */
object SemanticManifestTransformer {

    /**
     * Apply [DefaultTransformRuleSet] to [model] and return the transformed manifest.
     *
     * This is the canonical entry point — mirrors Python's `PydanticSemanticManifestTransformer.transform(model)`.
     */
    fun transform(model: SemanticManifest): SemanticManifest =
        transform(model, DefaultTransformRuleSet)

    /**
     * Apply a custom [ruleSet] to [model] and return the transformed manifest.
     *
     * Rules in each phase are applied in declared order; the secondary phase runs only after the
     * primary phase completes. Each rule receives the output of the previous one.
     */
    fun transform(model: SemanticManifest, ruleSet: SemanticManifestTransformRuleSet): SemanticManifest {
        var current = model
        for (rules in ruleSet.allRules) {
            for (rule in rules) {
                current = rule.transformModel(current)
            }
        }
        return current
    }
}
