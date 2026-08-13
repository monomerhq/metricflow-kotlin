package cc.monomer.metricflow.domain.query.naming

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.groupSpecByType
import cc.monomer.metricflow.domain.spec.pattern.MetricSpecPattern

/**
 * Naming scheme for bare metric names.
 *
 * Port of `metricflow_semantics.naming.metric_scheme.MetricNamingScheme`.
 *
 * Lowercase metric names without `(` characters round-trip through this
 * scheme as themselves. The scheme rejects any input containing `(`
 * because object-builder syntax (`Metric('bookings')`) is the
 * [ObjectBuilderNamingScheme]'s responsibility.
 */
class MetricNamingScheme : QueryItemNamingScheme {

    override fun inputStr(instanceSpec: InstanceSpec): String? {
        val specSet = groupSpecByType(instanceSpec)
        val names = specSet.metricSpecs.map { it.elementName }
        if (names.size != 1) {
            throw IllegalStateException("Did not get 1 name for $instanceSpec. Got $names")
        }
        return names.single()
    }

    override fun specPattern(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): MetricSpecPattern {
        val lower = inputStr.lowercase()
        if (!inputStrFollowsScheme(lower, semanticManifestLookup, queryItemLocation)) {
            throw IllegalArgumentException("${quote(inputStr)} does not follow this scheme.")
        }
        return MetricSpecPattern(
            metricReference = MetricReference(lower),
            descending = null,
        )
    }

    override fun inputStrFollowsScheme(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): Boolean = "(" !in inputStr

    override fun toString(): String = "${this::class.simpleName}(id()=0x${Integer.toHexString(System.identityHashCode(this))})"

    private fun quote(value: String): String = "'$value'"
}
