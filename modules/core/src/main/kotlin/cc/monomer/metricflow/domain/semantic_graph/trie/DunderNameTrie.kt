package cc.monomer.metricflow.domain.semantic_graph.trie

import cc.monomer.metricflow.domain.manifest.model.naming.DUNDER
import cc.monomer.metricflow.domain.semantic_graph.attribute_resolution.IndexedDunderName

/**
 * A trie-based data structure that stores dunder names and associated
 * [DunderNameDescriptor]s.
 *
 * Port of `metricflow_semantics/semantic_graph/trie_resolver/dunder_name_trie.py::DunderNameTrie`.
 *
 * Unlike a typical character-based trie, the segmentation key is the dunder
 * (`__`) separator. The trie that contains
 *
 *     listing__user
 *     listing__country
 *
 * is represented as
 *
 *     listing -> user
 *             -> country
 *
 * The trie simplifies the union/intersection operations used to enumerate the
 * available group-by items for a query.
 *
 * Python ships an abstract base and a mutable subclass. The Kotlin port
 * collapses to a single mutable class because callers always work with the
 * mutable view in practice.
 */
class DunderNameTrie(
    /**
     * Direct mapping from a name element at this trie level to the descriptor
     * stored at the corresponding child position (e.g. `"listing"` maps to the
     * descriptor for `listing` itself).
     */
    val nameElementToDescriptor: MutableMap<String, DunderNameDescriptor> = linkedMapOf(),
    /**
     * Mapping from a name element at this trie level to the trie holding the
     * subtree beneath that element (e.g. `"listing"` maps to the trie that
     * stores `country`, `user`, ...).
     */
    val nextNameElementToTrie: MutableMap<String, DunderNameTrie> = linkedMapOf(),
) {

    /** Return all dunder names + descriptors stored in this trie. */
    fun nameItems(): List<Pair<IndexedDunderName, DunderNameDescriptor>> = nameItems(maxLength = null)

    /**
     * Return dunder names + descriptors up to [maxLength] elements deep.
     * Pass `null` to traverse the full trie (equivalent to the no-arg overload).
     */
    fun nameItems(maxLength: Int?): List<Pair<IndexedDunderName, DunderNameDescriptor>> {
        val results = mutableListOf<Pair<IndexedDunderName, DunderNameDescriptor>>()
        collectNameItems(prefix = emptyList(), maxLength = maxLength, sink = results)
        return results
    }

    private fun collectNameItems(
        prefix: List<String>,
        maxLength: Int?,
        sink: MutableList<Pair<IndexedDunderName, DunderNameDescriptor>>,
    ) {
        for ((nameElement, descriptor) in nameElementToDescriptor) {
            sink.add(prefix + nameElement to descriptor)
        }
        if (maxLength != null && prefix.size + 1 >= maxLength) return
        for ((nameElement, subTrie) in nextNameElementToTrie) {
            subTrie.collectNameItems(prefix = prefix + nameElement, maxLength = maxLength, sink = sink)
        }
    }

    /** Return the dunder-form names that are represented by this trie. */
    fun dunderNames(): List<String> = nameItems().map { (parts, _) -> parts.joinToString(DUNDER) }

    /** Returns a deep mutable copy. */
    fun mutableCopy(): DunderNameTrie {
        val newDescriptors = LinkedHashMap(nameElementToDescriptor)
        val newSubTries = LinkedHashMap<String, DunderNameTrie>()
        for ((k, v) in nextNameElementToTrie) {
            newSubTries[k] = v.mutableCopy()
        }
        return DunderNameTrie(newDescriptors, newSubTries)
    }

    /**
     * Build out this trie by adding individual `(IndexedDunderName, DunderNameDescriptor)`
     * items.
     *
     * Port of `MutableDunderNameTrie.add_name_items`. The Python comment
     * explains the ambiguity semantics: if the same leaf name is added twice
     * with **any** descriptor at the same path, both are discarded as
     * ambiguous (the second insertion deletes the first and remembers the
     * deletion so further insertions are no-ops). This models the case where
     * the resolver finds two different join paths to the same logical
     * attribute — the query interface cannot disambiguate, so it drops the
     * item entirely.
     *
     * Note: even if the two descriptors would be "mergeable" by
     * [DunderNameDescriptor.isMergeable] (same element-type, time-grain,
     * date-part), Python still treats them as ambiguous. The intuition is
     * that the descriptors carry path-specific provenance (origin model IDs,
     * properties) and merging them silently would hide the ambiguity from the
     * user. This Kotlin port mirrors the same "ambiguity erases" behaviour.
     */
    fun addNameItems(items: Iterable<Pair<IndexedDunderName, DunderNameDescriptor>>) {
        val pathToAmbiguous: MutableMap<List<String>, MutableSet<String>> = mutableMapOf()
        for ((indexedName, descriptor) in items) {
            if (indexedName.isEmpty()) {
                throw IllegalStateException("Empty indexed dunder name when adding to trie")
            }
            val leafPath = indexedName.subList(0, indexedName.size - 1)
            val ambiguousAtLeaf = pathToAmbiguous.getOrPut(leafPath) { mutableSetOf() }

            var currentTrie = this
            for (index in indexedName.indices) {
                val element = indexedName[index]
                if (index == indexedName.size - 1) {
                    if (element in ambiguousAtLeaf) continue
                    val existing = currentTrie.nameElementToDescriptor[element]
                    if (existing == null) {
                        currentTrie.nameElementToDescriptor[element] = descriptor
                    } else {
                        currentTrie.nameElementToDescriptor.remove(element)
                        ambiguousAtLeaf.add(element)
                    }
                    continue
                }
                val next = currentTrie.nextNameElementToTrie.getOrPut(element) { DunderNameTrie() }
                currentTrie = next
            }
        }
    }

    /** Recursively decorate all descriptors with extra `derivedFromModelIds`. */
    fun updateDerivedFromModelIds(
        extra: List<cc.monomer.metricflow.domain.semantic_graph.SemanticModelId>,
    ) {
        for ((k, v) in nameElementToDescriptor.toMap()) {
            nameElementToDescriptor[k] = v.mergeDerivedFromModelIds(extra)
        }
        for ((_, sub) in nextNameElementToTrie) {
            sub.updateDerivedFromModelIds(extra)
        }
    }

    companion object {
        /**
         * Union names from a sequence of tries, merging descriptors for common
         * names. Mirrors Python's `union_merge_common`.
         */
        fun unionMergeCommon(tries: List<DunderNameTrie>): DunderNameTrie {
            if (tries.isEmpty()) return DunderNameTrie()
            if (tries.size == 1) return tries.first().mutableCopy()

            val newDescriptors = linkedMapOf<String, DunderNameDescriptor>()
            for (trie in tries) {
                for ((nameElement, descriptor) in trie.nameElementToDescriptor) {
                    val previous = newDescriptors[nameElement]
                    newDescriptors[nameElement] = if (previous == null) descriptor else previous.merge(descriptor)
                }
            }

            val subTriesByElement = linkedMapOf<String, MutableList<DunderNameTrie>>()
            for (trie in tries) {
                for ((nameElement, subTrie) in trie.nextNameElementToTrie) {
                    subTriesByElement.getOrPut(nameElement) { mutableListOf() }.add(subTrie)
                }
            }
            val newSubTries = linkedMapOf<String, DunderNameTrie>()
            for ((nameElement, sub) in subTriesByElement) {
                newSubTries[nameElement] = unionMergeCommon(sub)
            }

            return DunderNameTrie(newDescriptors, newSubTries)
        }

        /**
         * Union names from a sequence of tries, but **drop** descriptors whose
         * name element is common across the inputs. Mirrors Python's
         * `union_exclude_common`.
         *
         * Used when combining the two source branches of a simple metric — the
         * `local_model` branch and the `metric_time` branch. An attribute
         * reachable from both is considered ambiguous and excluded from the
         * union.
         */
        fun unionExcludeCommon(tries: List<DunderNameTrie>): DunderNameTrie {
            if (tries.isEmpty()) return DunderNameTrie()
            if (tries.size == 1) return tries.first().mutableCopy()

            val commonNames = tries.first().nameElementToDescriptor.keys.toMutableSet()
            for (trie in tries.drop(1)) {
                commonNames.retainAll(trie.nameElementToDescriptor.keys)
            }
            val newDescriptors = linkedMapOf<String, DunderNameDescriptor>()
            for (trie in tries) {
                for ((nameElement, descriptor) in trie.nameElementToDescriptor) {
                    if (nameElement in commonNames) continue
                    newDescriptors[nameElement] = descriptor
                }
            }
            val subTriesByElement = linkedMapOf<String, MutableList<DunderNameTrie>>()
            for (trie in tries) {
                for ((nameElement, subTrie) in trie.nextNameElementToTrie) {
                    subTriesByElement.getOrPut(nameElement) { mutableListOf() }.add(subTrie)
                }
            }
            val newSubTries = linkedMapOf<String, DunderNameTrie>()
            for ((nameElement, sub) in subTriesByElement) {
                newSubTries[nameElement] = unionExcludeCommon(sub)
            }
            return DunderNameTrie(newDescriptors, newSubTries)
        }

        /**
         * Intersect names from a sequence of tries, merging common descriptors.
         * Mirrors Python's `intersection_merge_common`.
         *
         * Items not present in every input trie are discarded — this models the
         * intersection used when resolving group-by items that must be
         * available for every metric in a multi-metric query.
         */
        fun intersectionMergeCommon(tries: List<DunderNameTrie>): DunderNameTrie {
            if (tries.isEmpty()) return DunderNameTrie()
            if (tries.size == 1) return tries.first().mutableCopy()

            val firstDescriptors = tries.first().nameElementToDescriptor
            val commonNameElements = firstDescriptors.keys.toMutableSet()
            for (trie in tries.drop(1)) {
                commonNameElements.retainAll(trie.nameElementToDescriptor.keys)
            }
            val newDescriptors = linkedMapOf<String, DunderNameDescriptor>()
            for (nameElement in commonNameElements) {
                var merged = tries.first().nameElementToDescriptor.getValue(nameElement)
                for (trie in tries.drop(1)) {
                    merged = merged.merge(trie.nameElementToDescriptor.getValue(nameElement))
                }
                newDescriptors[nameElement] = merged
            }

            // Intersect the sub-tries on the name elements that are common.
            val firstSubKeys = tries.first().nextNameElementToTrie.keys.toMutableSet()
            for (trie in tries.drop(1)) {
                firstSubKeys.retainAll(trie.nextNameElementToTrie.keys)
            }
            val newSubTries = linkedMapOf<String, DunderNameTrie>()
            for (nameElement in firstSubKeys) {
                val subTries = tries.map { it.nextNameElementToTrie.getValue(nameElement) }
                newSubTries[nameElement] = intersectionMergeCommon(subTries)
            }
            return DunderNameTrie(newDescriptors, newSubTries)
        }
    }
}
