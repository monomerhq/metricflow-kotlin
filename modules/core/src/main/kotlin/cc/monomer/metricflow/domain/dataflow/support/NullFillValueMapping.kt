package cc.monomer.metricflow.domain.dataflow.support

import cc.monomer.metricflow.common.util.Mergeable
import cc.monomer.metricflow.domain.spec.SimpleMetricInputSpec

/**
 * Mapping from simple-metric input element name to the null-fill value declared for that input.
 *
 * Port of `metricflow.dataflow.builder.aggregation_helper.NullFillValueMapping`. Used by
 * [cc.monomer.metricflow.domain.dataflow.nodes.AggregateSimpleMetricInputsNode] so the
 * downstream [cc.monomer.metricflow.domain.dataflow.nodes.ComputeMetricsNode] can render
 * the right `COALESCE` expressions.
 *
 * Implemented as an ordered list of `(elementName, fillValue)` pairs rather than a `Map` so the
 * mapping preserves Python iteration order (used in pretty-printing and merge resolution).
 *
 * **Note on placement.** Python keeps this in `dataflow/builder/`. We host it under
 * `:domain:dataflow/support` because the node API depends on it; the W9b builder still references
 * the same type.
 */
data class NullFillValueMapping(
    val elementNameAndNullFillValueItems: List<Pair<String, Int?>>,
) : Mergeable<NullFillValueMapping> {

    /** Same data, projected as a `LinkedHashMap` (preserves insertion order). */
    val elementNameToNullFillValue: Map<String, Int?>
        get() = LinkedHashMap<String, Int?>().also {
            for ((k, v) in elementNameAndNullFillValueItems) it[k] = v
        }

    /** Returns `true` iff `other` sets a different null-fill value for any shared element name. */
    fun hasConflict(other: NullFillValueMapping): Boolean = conflictingElementNames(other).isNotEmpty()

    /** Apply this mapping to a spec — returns the spec with `fillNullsWith` set, or `null`. */
    fun nullFillValueSpec(spec: SimpleMetricInputSpec): SimpleMetricInputSpec? {
        val value = elementNameToNullFillValue[spec.elementName] ?: return null
        return SimpleMetricInputSpec(elementName = spec.elementName, fillNullsWith = value)
    }

    override fun merge(other: NullFillValueMapping): NullFillValueMapping {
        if (hasConflict(other)) {
            error("Can't merge fill value mappings with conflicting items. Conflicts should have been checked before merging.")
        }
        val merged = LinkedHashMap(elementNameToNullFillValue)
        for ((k, v) in other.elementNameToNullFillValue) merged[k] = v
        return create(merged)
    }

    private fun conflictingElementNames(other: NullFillValueMapping): List<String> {
        val self = elementNameToNullFillValue
        val them = other.elementNameToNullFillValue
        return self.keys.intersect(them.keys).filter { self[it] != them[it] }
    }

    companion object {
        /** Empty mapping, the [Mergeable] identity element. */
        val EMPTY: NullFillValueMapping = NullFillValueMapping(emptyList())

        /** Construct from an ordered map. */
        fun create(elementNameToNullFillValue: Map<String, Int?>): NullFillValueMapping =
            NullFillValueMapping(elementNameToNullFillValue.map { (k, v) -> k to v })
    }
}
