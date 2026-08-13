package cc.monomer.metricflow.domain.dataflow.builder

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec

/**
 * Mapping from simple-metric-input element name → alias (output column name).
 *
 * Port of `metricflow.dataflow.builder.aggregation_helper.InstanceAliasMapping`. Used by the
 * builder when emitting `AggregateSimpleMetricInputsNode`s that need to rename outputs (e.g.
 * for derived metrics that reference an input under a different name).
 *
 * Like [cc.monomer.metricflow.domain.dataflow.support.NullFillValueMapping], the items
 * are kept as an ordered list of pairs for stable iteration order in pretty-printing and merge.
 */
data class InstanceAliasMapping(
    val elementNameAndAliasItems: List<Pair<String, String>>,
) : Mergeable<InstanceAliasMapping> {

    /** Same data, projected as a `LinkedHashMap` (preserves insertion order). */
    val elementNameToAlias: Map<String, String>
        get() = LinkedHashMap<String, String>().also {
            for ((k, v) in elementNameAndAliasItems) it[k] = v
        }

    /** Returns the spec with its alias applied, or `null` if none registered. */
    fun aliasedSpec(spec: SimpleMetricInputSpec): SimpleMetricInputSpec? {
        val alias = elementNameToAlias[spec.elementName] ?: return null
        return SimpleMetricInputSpec(elementName = alias, fillNullsWith = spec.fillNullsWith)
    }

    /** `true` iff `other` assigns a different alias for any common element. */
    fun hasConflict(other: InstanceAliasMapping): Boolean {
        val mine = elementNameToAlias
        val theirs = other.elementNameToAlias
        val otherAliases = theirs.values.toSet()
        for ((name, alias) in mine) {
            val theirAlias = theirs[name]
            if (theirAlias != null) {
                if (theirAlias != alias) return true
            } else if (alias in otherAliases) {
                return true
            }
        }
        return false
    }

    override fun merge(other: InstanceAliasMapping): InstanceAliasMapping {
        if (hasConflict(other)) error("Can't merge alias mappings with conflicting items")
        val merged = LinkedHashMap(elementNameToAlias)
        for ((k, v) in other.elementNameToAlias) merged[k] = v
        return create(merged)
    }

    companion object {
        /** Empty mapping, the [Mergeable] identity element. */
        val EMPTY: InstanceAliasMapping = InstanceAliasMapping(emptyList())

        /** Construct from an ordered map. */
        fun create(elementNameToAlias: Map<String, String>): InstanceAliasMapping =
            InstanceAliasMapping(elementNameToAlias.map { (k, v) -> k to v })
    }
}
