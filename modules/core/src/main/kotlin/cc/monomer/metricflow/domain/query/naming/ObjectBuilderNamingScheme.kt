package cc.monomer.metricflow.domain.query.naming

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern

/**
 * Object-builder naming scheme: `Dimension('listing__country')`,
 * `TimeDimension('metric_time', 'day')`, `Metric('bookings', group_by=['user'])`.
 *
 * Port of `metricflow_semantics.naming.object_builder_scheme.ObjectBuilderNamingScheme`.
 *
 * **Partial port.** Python's implementation parses the Jinja-style template
 * `{{ Dimension('...') }}` via
 * `metricflow_semantic_interfaces.parsing.where_filter.jinja_object_parser.JinjaObjectParser`.
 * That parser has not yet been ported (a follow-up wave will land it, see
 * `:domain:manifest:validation/WhereFiltersAreParseable` for the same
 * deferral). Until the parser arrives, this scheme:
 *
 * - rejects all candidate inputs from [inputStrFollowsScheme] (so the query
 *   parser falls through to [DunderNamingScheme] / [MetricNamingScheme] —
 *   the supported subset of corpus today),
 * - throws when [specPattern] is invoked,
 * - returns `null` from [inputStr] for every spec (signals "scheme cannot
 *   render this", which Python uses for sentinel "skip me" semantics).
 *
 * When the Jinja parser ports, replace the bodies with the Python-parity
 * implementations — the structure / regex / type-dispatch logic is in
 * `python_oracle/upstream/metricflow_semantics/naming/object_builder_scheme.py`.
 */
class ObjectBuilderNamingScheme : QueryItemNamingScheme {

    override fun inputStr(instanceSpec: InstanceSpec): String? {
        // ObjectBuilderNameConverter not yet ported. Returns null to indicate
        // "scheme cannot accommodate this spec" — Python's documented escape
        // hatch for unsupported cases.
        return null
    }

    override fun specPattern(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): SpecPattern {
        throw UnsupportedOperationException(
            "ObjectBuilderNamingScheme.specPattern is awaiting the JinjaObjectParser port " +
                "(see :domain:manifest:validation/WhereFiltersAreParseable). " +
                "Input was: '$inputStr'.",
        )
    }

    override fun inputStrFollowsScheme(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): Boolean {
        // The regex itself can be checked without the parser, but the Python
        // implementation also calls the parser to verify the call-parameter
        // sets are valid. Returning false until the parser ports.
        return false
    }

    override fun toString(): String = "${this::class.simpleName}(id()=0x${Integer.toHexString(System.identityHashCode(this))})"

    companion object {
        /**
         * Regex that matches the surface form `Dimension(...)`,
         * `TimeDimension(...)`, `Entity(...)`, `Metric(...)`. Exposed for
         * future Jinja-parser-backed reactivation of the scheme.
         */
        val NAME_REGEX: Regex = Regex("\\A(Dimension|TimeDimension|Entity|Metric)\\(.*\\)\\Z")
    }
}
