package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.common.util.collections.FrozenOrderedSet
import cc.monomer.metricflow.common.util.collections.MutableOrderedSet
import cc.monomer.metricflow.domain.lookup.GroupByItemSetFilter
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameTrie
import cc.monomer.metricflow.domain.spec.InstanceSpec
import cc.monomer.metricflow.domain.spec.LinkableInstanceSpec
import cc.monomer.metricflow.domain.spec.pattern.SpecPattern
import cc.monomer.metricflow.domain.spec.where.LinkableSpecGroup

/**
 * Implementation of `BaseGroupByItemSet` based on [AnnotatedSpec].
 *
 * Port of `metricflow_semantics/semantic_graph/attribute_resolution/group_by_item_set.py::GroupByItemSet`.
 *
 * Provides the set algebra (intersection / union / filter) used by the
 * resolver when computing the available group-by items for a metric (or set
 * of metrics).
 *
 * Note on the deferred W7b spec: Python's `WhereFilterSpec` carries a
 * `element_set: GroupByItemSet`. The Kotlin [`WhereFilterSpec`][cc.monomer.metricflow.domain.spec.where.WhereFilterSpec]
 * was simplified in W7b to a `List<LinkableInstanceSpec>` because this
 * `GroupByItemSet` type wasn't yet ported. W8 (`:domain:query` + the
 * where-filter factory) will introduce a wrapper that pairs the W7b
 * [WhereFilterSpec] with this [GroupByItemSet] — see this module's README.
 */
data class GroupByItemSet(val annotatedSpecs: List<AnnotatedSpec>) : LinkableSpecGroup {

    /** Returns this set as a mapping from dunder name to [AnnotatedSpec]. */
    val dunderNameToAnnotatedSpec: Map<String, AnnotatedSpec> by lazy {
        annotatedSpecs.associateBy { it.spec.dunderName }
    }

    /** True iff this maps to no specs. */
    override val isEmpty: Boolean get() = annotatedSpecs.isEmpty()

    /** The materialised spec list. */
    override val specs: List<LinkableInstanceSpec> get() = annotatedSpecs.map { it.spec }

    /** The set of semantic models from which the contained specs are derived. */
    val derivedFromSemanticModels: List<SemanticModelReference>
        get() = FrozenOrderedSet(
            annotatedSpecs.flatMap { it.derivedFromSemanticModels },
        ).toList()

    /** Intersection — keep only annotated specs whose dunder name is in every input set. */
    fun intersection(vararg others: GroupByItemSet): GroupByItemSet {
        if (others.isEmpty()) return this
        val commonKeys = MutableOrderedSet(dunderNameToAnnotatedSpec.keys)
        for (other in others) {
            commonKeys.retainAll(other.dunderNameToAnnotatedSpec.keys)
        }
        val merged = linkedMapOf<String, AnnotatedSpec>()
        for (key in commonKeys) {
            var spec = dunderNameToAnnotatedSpec.getValue(key)
            for (other in others) {
                spec = spec.merge(other.dunderNameToAnnotatedSpec.getValue(key))
            }
            merged[key] = spec
        }
        return createFromMapping(merged)
    }

    /** Union — merge annotated specs across input sets by dunder name. */
    fun union(vararg others: GroupByItemSet): GroupByItemSet {
        val merged = LinkedHashMap(dunderNameToAnnotatedSpec)
        for (other in others) {
            for ((name, spec) in other.dunderNameToAnnotatedSpec) {
                merged[name] = merged[name]?.merge(spec) ?: spec
            }
        }
        return createFromMapping(merged)
    }

    /**
     * Filter annotated specs by element name + property allow/deny lists.
     *
     * Matches Python's filter semantics: keep only annotated specs whose
     * element name is in the allowlist (when non-null), whose properties
     * intersect with the allowlist set, and whose properties do not intersect
     * with the denylist set.
     */
    fun filter(elementFilter: GroupByItemSetFilter): GroupByItemSet {
        val allowlist = elementFilter.elementNameAllowlist
        val denylist = elementFilter.anyPropertiesDenylist
        val results = mutableListOf<AnnotatedSpec>()
        for (annotated in annotatedSpecs) {
            if (allowlist != null && annotated.elementName !in allowlist) continue
            val propertySet = annotated.propertySet
            if (elementFilter.anyPropertiesAllowlist.intersect(propertySet).isEmpty()) continue
            if (denylist.isNotEmpty() && denylist.intersect(propertySet).isNotEmpty()) continue
            results.add(annotated)
        }
        return GroupByItemSet(results)
    }

    /** Filter by a sequence of [SpecPattern]s. Each pattern further narrows the result. */
    fun filterBySpecPatterns(specPatterns: List<SpecPattern>): GroupByItemSet {
        if (specPatterns.isEmpty()) return this
        val specToAnnotated: Map<InstanceSpec, AnnotatedSpec> =
            annotatedSpecs.associateBy { it.spec }
        var specs: List<InstanceSpec> = specToAnnotated.keys.toList()
        for (pattern in specPatterns) {
            specs = pattern.match(specs)
        }
        return GroupByItemSet(specs.mapNotNull { specToAnnotated[it] })
    }

    companion object {
        /** Build by dedup-merging the supplied annotated specs by dunder name. */
        fun create(vararg annotatedSpecs: AnnotatedSpec): GroupByItemSet = create(annotatedSpecs.toList())

        /** Build by dedup-merging the supplied annotated specs by dunder name. */
        fun create(annotatedSpecs: Iterable<AnnotatedSpec>): GroupByItemSet {
            val collected = linkedMapOf<String, AnnotatedSpec>()
            for (annotated in annotatedSpecs) {
                val dunderName = annotated.spec.dunderName
                collected[dunderName] = collected[dunderName]?.merge(annotated) ?: annotated
            }
            return createFromMapping(collected)
        }

        /** Build directly from a mapping. */
        fun createFromMapping(mapping: Map<String, AnnotatedSpec>): GroupByItemSet =
            GroupByItemSet(mapping.values.toList())

        /** Build by exhausting one or more dunder-name tries. */
        fun createFromTrie(vararg dunderNameTries: DunderNameTrie): GroupByItemSet {
            val results = mutableListOf<AnnotatedSpec>()
            for (trie in dunderNameTries) {
                for ((indexedName, descriptor) in trie.nameItems()) {
                    results.addAll(AnnotatedSpec.createFromIndexedDunderName(indexedName, descriptor))
                }
            }
            return GroupByItemSet(results)
        }

        /** The empty set. */
        val EMPTY: GroupByItemSet = GroupByItemSet(emptyList())
    }
}
