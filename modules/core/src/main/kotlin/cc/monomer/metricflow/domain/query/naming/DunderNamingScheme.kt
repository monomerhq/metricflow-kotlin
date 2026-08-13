package cc.monomer.metricflow.domain.query.naming

import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.InstanceSpecSet
import cc.monomer.metricflow.domain.spec.InstanceSpecSetTransform
import cc.monomer.metricflow.domain.spec.groupSpecByType
import cc.monomer.metricflow.domain.spec.naming.StructuredLinkableSpecName
import cc.monomer.metricflow.domain.spec.pattern.EntityLinkPattern
import cc.monomer.metricflow.domain.spec.pattern.SpecPatternParameterSet
import cc.monomer.metricflow.domain.spec.pattern.TimeDimensionPattern

/**
 * Dundered-name scheme: `listing__country` / `listing__ds__day` / etc.
 *
 * Port of `metricflow_semantics.naming.dunder_scheme.DunderNamingScheme`.
 *
 * Case-insensitive. The Python class drops support for `date_part`
 * suffixes when generating spec patterns, but uses them when re-rendering
 * a spec back into a string. The Kotlin port preserves both directions.
 */
class DunderNamingScheme : QueryItemNamingScheme {

    override fun inputStr(instanceSpec: InstanceSpec): String? {
        val specSet = groupSpecByType(instanceSpec)
        // Dunder syntax not supported for querying date_part — see Python parity.
        for (timeDimensionSpec in specSet.timeDimensionSpecs) {
            if (timeDimensionSpec.datePart != null) return null
        }
        val names = DunderNameTransform.transform(specSet)
        check(names.size == 1) { "Did not get 1 name for $instanceSpec. Got $names" }
        return names.single()
    }

    override fun specPattern(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): EntityLinkPattern {
        if (!inputStrFollowsScheme(inputStr, semanticManifestLookup, queryItemLocation)) {
            throw IllegalArgumentException("'$inputStr' does not follow this scheme.")
        }
        val normalised = inputStr.lowercase()
        val structured = StructuredLinkableSpecName.fromName(
            qualifiedName = normalised,
            customGranularityNames = semanticManifestLookup.customGranularities.keys.toList(),
        )
        // Passing DatePart via dunder syntax is not supported — null.
        val fields = TimeDimensionPattern.getFieldsToCompare(
            timeGranularityName = structured.timeGranularityName,
            datePart = null,
        )
        return EntityLinkPattern(
            parameterSet = SpecPatternParameterSet.fromParameters(
                fieldsToCompare = fields,
                elementName = structured.elementName,
                entityLinks = structured.entityLinks,
                timeGranularityName = structured.timeGranularityName,
                datePart = null,
                metricSubqueryEntityLinks = null,
                descending = null,
            ),
        )
    }

    override fun inputStrFollowsScheme(
        inputStr: String,
        semanticManifestLookup: SemanticManifestLookup,
        queryItemLocation: QueryItemLocation,
    ): Boolean {
        // Case-insensitive scheme.
        val normalised = inputStr.lowercase()
        if (!INPUT_REGEX.matches(normalised)) return false
        val parts = normalised.split(DUNDER)
        for (datePart in DatePart.entries) {
            if (parts.last() == datePartSuffix(datePart)) {
                // "Dunder syntax not supported for querying date_part" (Python parity).
                return false
            }
        }
        return true
    }

    override fun toString(): String = "${this::class.simpleName}(id()=0x${Integer.toHexString(System.identityHashCode(this))})"

    companion object {
        private val INPUT_REGEX = Regex("\\A[a-z]([a-z0-9_])*[a-z0-9]\\Z")

        /** Suffix used for names that pin a `date_part`. */
        fun datePartSuffix(datePart: DatePart): String = "extract_${datePart.value}"
    }
}

/**
 * Transform that emits the dunder name for every linkable spec.
 *
 * Port of the private `_DunderNameTransform` inside Python's `dunder_scheme.py`.
 *
 * Inputs are an [InstanceSpecSet] (typically of size 1 — see
 * [DunderNamingScheme.inputStr]). Outputs are the sorted list of dunder
 * names. Time-dimension specs add a granularity or date-part suffix; other
 * linkable specs use the bare element name appended to the entity-link
 * chain.
 */
internal object DunderNameTransform : InstanceSpecSetTransform<List<String>> {
    override fun transform(specSet: InstanceSpecSet): List<String> {
        val names = mutableListOf<String>()

        for (timeDimensionSpec in specSet.timeDimensionSpecs) {
            val items = timeDimensionSpec.entityLinks.map { it.elementName }.toMutableList()
            items.add(timeDimensionSpec.elementName)
            val datePart = timeDimensionSpec.datePart
            if (datePart != null) {
                items.add(DunderNamingScheme.datePartSuffix(datePart))
            } else {
                val grain = timeDimensionSpec.timeGranularity
                    ?: throw IllegalStateException(
                        "No time granularity or date part set for time dimension spec $timeDimensionSpec. " +
                            "This indicates internal misconfiguration.",
                    )
                items.add(grain.name)
            }
            names.add(items.joinToString(DUNDER))
        }

        val others = specSet.entitySpecs + specSet.dimensionSpecs + specSet.groupByMetricSpecs
        for (spec in others) {
            val items = spec.entityLinks.map { it.elementName }.toMutableList()
            items.add(spec.elementName)
            names.add(items.joinToString(DUNDER))
        }

        return names.sorted()
    }
}
