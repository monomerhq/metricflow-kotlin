package cc.monomer.metricflow.domain.semantic_graph.attribute_resolution

import cc.monomer.metricflow.domain.lookup.LinkableElementType
import cc.monomer.metricflow.domain.lookup.SemanticManifestLookup
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import cc.monomer.metricflow.domain.semantic_graph.SemanticManifestGraphLookup
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the W11 DFS resolver, exercising the corpus simple manifest.
 *
 * The behaviour we want to lock in:
 *
 * - Multi-hop dimensions appear with the correct `entity_links` chain
 *   (e.g. `listing__user__company_name`).
 * - The path-final semantic model is the canonical origin
 *   (`recipe.joined_model_ids[-1]`).
 * - Repeated-dunder ambiguity blocks pathological recipes.
 * - Multi-path attributes whose descriptors disagree are dropped (the
 *   `add_name_items` ambiguity rule).
 */
class PathEnumeratorTest {

    private fun loadSimpleManifest(): SemanticManifest {
        val repoRoot = System.getProperty("metricflow.repoRoot")
            ?: File("..").absolutePath
        val file = File(repoRoot, "corpus/manifests/simple_manifest.json")
        require(file.isFile) { "simple_manifest.json fixture not found at $file" }
        return ManifestJson.decodeFromString(SemanticManifest.serializer(), file.readText())
    }

    @Test
    fun `bookings metric exposes the multi-hop listing__user__company_name dimension`() {
        val manifest = loadSimpleManifest()
        val lookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(lookup)
        val resolver = graphLookup.groupByItemSetResolver

        val set = resolver.resolveAvailableItemsForMetric(MetricReference("bookings"))
        val match = set.annotatedSpecs.firstOrNull {
            it.spec.dunderName == "listing__user__company_name"
        }
        assertTrue(match != null, "Expected multi-hop dimension listing__user__company_name")
        assertEquals(LinkableElementType.DIMENSION, match.elementType)
        assertEquals(listOf("listing", "user"), match.entityLinkNames)
        // Path-final model is the canonical origin — Python's
        // `recipe.joined_model_ids[-1]`.
        assertEquals(listOf("companies"), match.originSemanticModelNames)
    }

    @Test
    fun `bookings metric resolves listing entity to its primary-owning model`() {
        val manifest = loadSimpleManifest()
        val lookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(lookup)
        val resolver = graphLookup.groupByItemSetResolver

        val set = resolver.resolveAvailableItemsForMetric(MetricReference("bookings"))
        // The `listing` entity reached at depth 1 has origin = bookings_source
        // (the local model from which the simple-metric branch starts).
        val listing = set.annotatedSpecs.firstOrNull {
            it.elementType == LinkableElementType.ENTITY && it.spec.dunderName == "listing"
        }
        assertTrue(listing != null, "Expected listing entity spec at depth 1")
        assertEquals(listOf("bookings_source"), listing.originSemanticModelNames)
    }

    @Test
    fun `multi-metric query intersection retains attributes common to every metric`() {
        val manifest = loadSimpleManifest()
        val lookup = SemanticManifestLookup(manifest)
        val graphLookup = SemanticManifestGraphLookup(lookup)
        val resolver = graphLookup.groupByItemSetResolver

        val bookings = resolver.resolveAvailableItemsForMetric(MetricReference("bookings"))
        val views = resolver.resolveAvailableItemsForMetric(MetricReference("views"))
        val intersected = bookings.intersection(views)

        // listing__user is reachable from both bookings (via listings_latest)
        // and views (via the same listings_latest path) — origin survives the merge.
        val user = intersected.annotatedSpecs.firstOrNull {
            it.spec.dunderName == "listing__user"
        }
        assertTrue(user != null, "Expected listing__user in bookings ∩ views")
        assertTrue("listings_latest" in user.originSemanticModelNames)
    }
}
