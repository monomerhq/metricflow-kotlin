package cc.monomer.metricflow.domain.query.naming

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern

/**
 * Strategy for the user-facing names of group-by items and metrics in a
 * query.
 *
 * Port of `metricflow_semantics.naming.naming_scheme.QueryItemNamingScheme`.
 *
 * Concrete schemes include:
 *
 * - [DunderNamingScheme] — `listing__country` style.
 * - [MetricNamingScheme] — bare metric name.
 * - [ObjectBuilderNamingScheme] — `Dimension('listing__country').grain('day')`
 *   style (Jinja-parser backed).
 *
 * The interface lets the query parser try schemes in order and pick the
 * first one that claims the input via [inputStrFollowsScheme].
 */
interface QueryItemNamingScheme {

    /**
     * Render [instanceSpec] back to the user-facing name in this scheme, or
     * `null` if the scheme cannot represent the spec (e.g. dunder syntax
     * does not encode `date_part`).
     */
    fun inputStr(instanceSpec: InstanceSpec): String?

    /**
     * Build a [SpecPattern] that matches the given user input.
     *
     * Implementations should call [inputStrFollowsScheme] first and raise an
     * `InvalidQuerySyntax` (`IllegalArgumentException` in Kotlin) when the
     * caller passes a string that doesn't fit.
     */
    fun specPattern(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): SpecPattern

    /**
     * True iff [inputStr] is well-formed under this scheme.
     */
    fun inputStrFollowsScheme(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): Boolean
}
