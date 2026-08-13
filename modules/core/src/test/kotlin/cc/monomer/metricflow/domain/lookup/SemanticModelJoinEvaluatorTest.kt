package cc.monomer.metricflow.domain.lookup

import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticModelJoinEvaluatorTest {

    @Test
    fun `valid joins list contains primary primary join`() {
        // Sanity-check the structural assertion table; the precise set is documented in the
        // class KDoc.
        val pp = SemanticModelEntityJoinType(
            leftEntityType = cc.monomer.metricflow.domain.manifest.model.enums.EntityType.PRIMARY,
            rightEntityType = cc.monomer.metricflow.domain.manifest.model.enums.EntityType.PRIMARY,
        )
        assertTrue(pp in SemanticModelJoinEvaluator.VALID_ENTITY_JOINS)
    }

    @Test
    fun `valid join primary to foreign returns null`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val evaluator = SemanticModelJoinEvaluator(lookup)

        // bookings_source `booking` is PRIMARY, listings_source has no `booking` entity → null.
        val result = evaluator.getValidSemanticModelEntityJoinType(
            leftSemanticModelReference = SemanticModelReference("bookings_source"),
            rightSemanticModelReference = SemanticModelReference("listings_source"),
            onEntityReference = EntityReference("booking"),
        )
        assertNull(result)
    }

    @Test
    fun `valid join foreign to primary works`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val evaluator = SemanticModelJoinEvaluator(lookup)

        // bookings_source `listing` is FOREIGN; listings_source `listing` is PRIMARY → valid.
        val result = evaluator.getValidSemanticModelEntityJoinType(
            leftSemanticModelReference = SemanticModelReference("bookings_source"),
            rightSemanticModelReference = SemanticModelReference("listings_source"),
            onEntityReference = EntityReference("listing"),
        )
        assertNotNull(result)
        assertEquals(
            cc.monomer.metricflow.domain.manifest.model.enums.EntityType.FOREIGN,
            result.leftEntityType,
        )
        assertEquals(
            cc.monomer.metricflow.domain.manifest.model.enums.EntityType.PRIMARY,
            result.rightEntityType,
        )
        assertTrue(
            evaluator.isValidSemanticModelJoin(
                leftSemanticModelReference = SemanticModelReference("bookings_source"),
                rightSemanticModelReference = SemanticModelReference("listings_source"),
                onEntityReference = EntityReference("listing"),
            ),
        )
    }

    @Test
    fun `missing model returns null`() {
        val lookup = SemanticModelLookup(LookupFixtures.manifest(), customGranularities = emptyMap())
        val evaluator = SemanticModelJoinEvaluator(lookup)
        assertNull(
            evaluator.getValidSemanticModelEntityJoinType(
                leftSemanticModelReference = SemanticModelReference("ghost"),
                rightSemanticModelReference = SemanticModelReference("listings_source"),
                onEntityReference = EntityReference("listing"),
            ),
        )
    }
}
