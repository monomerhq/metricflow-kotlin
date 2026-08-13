package cc.monomer.metricflow.domain.semantic_graph

import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameDescriptor
import cc.monomer.metricflow.domain.semantic_graph.trie.DunderNameTrie
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DunderNameTrieTest {

    private fun makeDescriptor(): DunderNameDescriptor = DunderNameDescriptor(
        elementType = LinkableElementType.DIMENSION,
        timeGrain = null,
        datePart = null,
        elementProperties = emptyList(),
        originModelIds = emptyList(),
        derivedFromModelIds = emptyList(),
        entityKeyQueriesForGroupByMetric = emptyList(),
    )

    @Test
    fun `nameItems returns stored entries`() {
        val trie = DunderNameTrie()
        trie.nameElementToDescriptor["listing"] = makeDescriptor()
        trie.nameElementToDescriptor["country"] = makeDescriptor()
        val items = trie.nameItems()
        assertEquals(2, items.size)
        assertTrue(items.any { it.first == listOf("listing") })
        assertTrue(items.any { it.first == listOf("country") })
    }

    @Test
    fun `dunderNames flattens to dunder strings`() {
        val trie = DunderNameTrie()
        trie.nameElementToDescriptor["listing"] = makeDescriptor()
        val sub = DunderNameTrie()
        sub.nameElementToDescriptor["country"] = makeDescriptor()
        trie.nextNameElementToTrie["listing"] = sub
        val names = trie.dunderNames()
        assertTrue("listing" in names)
        assertTrue("listing__country" in names)
    }

    @Test
    fun `mutable copy is deep`() {
        val trie = DunderNameTrie()
        trie.nameElementToDescriptor["listing"] = makeDescriptor()
        val sub = DunderNameTrie()
        sub.nameElementToDescriptor["country"] = makeDescriptor()
        trie.nextNameElementToTrie["listing"] = sub

        val copy = trie.mutableCopy()
        copy.nameElementToDescriptor["new_top"] = makeDescriptor()
        assertTrue("new_top" !in trie.nameElementToDescriptor)
    }

    @Test
    fun `unionMergeCommon merges descriptors across tries`() {
        val a = DunderNameTrie().apply {
            nameElementToDescriptor["listing"] = makeDescriptor()
        }
        val b = DunderNameTrie().apply {
            nameElementToDescriptor["country"] = makeDescriptor()
        }
        val merged = DunderNameTrie.unionMergeCommon(listOf(a, b))
        assertTrue("listing" in merged.nameElementToDescriptor.keys)
        assertTrue("country" in merged.nameElementToDescriptor.keys)
    }

    @Test
    fun `intersectionMergeCommon keeps only shared keys`() {
        val a = DunderNameTrie().apply {
            nameElementToDescriptor["listing"] = makeDescriptor()
            nameElementToDescriptor["shared"] = makeDescriptor()
        }
        val b = DunderNameTrie().apply {
            nameElementToDescriptor["country"] = makeDescriptor()
            nameElementToDescriptor["shared"] = makeDescriptor()
        }
        val merged = DunderNameTrie.intersectionMergeCommon(listOf(a, b))
        assertEquals(setOf("shared"), merged.nameElementToDescriptor.keys)
    }

    @Test
    fun `unionExcludeCommon drops descriptors present in every input`() {
        val a = DunderNameTrie().apply {
            nameElementToDescriptor["listing"] = makeDescriptor()
            nameElementToDescriptor["shared"] = makeDescriptor()
        }
        val b = DunderNameTrie().apply {
            nameElementToDescriptor["country"] = makeDescriptor()
            nameElementToDescriptor["shared"] = makeDescriptor()
        }
        val merged = DunderNameTrie.unionExcludeCommon(listOf(a, b))
        // `shared` is common → dropped. `listing` and `country` survive.
        assertEquals(setOf("listing", "country"), merged.nameElementToDescriptor.keys)
    }

    @Test
    fun `addNameItems drops a leaf that is added twice (ambiguity)`() {
        val trie = DunderNameTrie()
        trie.addNameItems(
            listOf(
                listOf("listing", "user") to makeDescriptor(),
                listOf("listing", "user") to makeDescriptor(),
            ),
        )
        // The second insertion of `user` at the same path is ambiguous —
        // both insertions are erased.
        val items = trie.nameItems()
        assertTrue(items.none { it.first == listOf("listing", "user") })
    }

    @Test
    fun `addNameItems keeps a single unambiguous insertion`() {
        val trie = DunderNameTrie()
        trie.addNameItems(
            listOf(
                listOf("listing", "user") to makeDescriptor(),
            ),
        )
        val items = trie.nameItems()
        assertEquals(1, items.size)
        assertEquals(listOf("listing", "user"), items.first().first)
    }
}
