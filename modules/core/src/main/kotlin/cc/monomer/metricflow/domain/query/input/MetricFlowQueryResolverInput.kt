package cc.monomer.metricflow.domain.query.input

import cc.monomer.metricflow.domain.query.naming.QueryItemNamingScheme
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern

/**
 * Describes the pattern + naming-scheme pair associated with a query input.
 *
 * Port of
 * `metricflow_semantics.query.resolver_inputs.base_resolver_inputs.InputPatternDescription`.
 *
 * Some query inputs (e.g. `group_by_names=["listing__country"]`) are
 * converted into spec patterns through a naming scheme. The naming scheme
 * is retained alongside the produced pattern so error messages and
 * suggestion generators can re-encode the pattern back to a user-readable
 * form.
 */
data class InputPatternDescription(
    val namingScheme: QueryItemNamingScheme,
    val specPattern: SpecPattern,
)

/**
 * Base interface for every input flowing into the query resolver.
 *
 * Port of
 * `metricflow_semantics.query.resolver_inputs.base_resolver_inputs.MetricFlowQueryResolverInput`.
 *
 * Concrete variants live in [cc.monomer.metricflow.domain.query.input]
 * (sealed via subclassing in Python but expressed in Kotlin as a `sealed`
 * interface for compile-time exhaustiveness).
 */
sealed interface MetricFlowQueryResolverInput {
    /** Human-readable description for error messages. */
    val uiDescription: String

    /**
     * Pattern description if this input maps to a single spec pattern.
     *
     * Inputs like the metrics or group-by items have this set; inputs like
     * the limit or apply-group-by return `null`.
     */
    val inputPatternDescription: InputPatternDescription?
        get() = null
}
