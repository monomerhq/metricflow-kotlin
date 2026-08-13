package cc.monomer.metricflow.domain.manifest.validation

import cc.monomer.metricflow.domain.manifest.model.ProjectConfiguration
import cc.monomer.metricflow.domain.manifest.model.SemanticManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Unit tests for the [SemanticManifestValidator] orchestrator + [DefaultValidationRules]. */
class SemanticManifestValidatorTest {

    @Test
    fun `empty manifest reports both NonEmptyRule errors`() {
        val manifest = SemanticManifest(
            semanticModels = emptyList(),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(),
        )
        val results = SemanticManifestValidator.withDefaultRules().validate(manifest)
        assertTrue(results.hasBlockingIssues)
        val errorMessages = results.errors.map { it.message }
        assertTrue("No semantic models present in the model." in errorMessages)
        assertTrue("No metrics present in the model." in errorMessages)
    }

    @Test
    fun `validator never throws on a buggy rule`() {
        val buggyRule = SemanticManifestValidationRule { error("kaboom") }
        val validator = SemanticManifestValidator(listOf(buggyRule))
        val manifest = SemanticManifest(
            semanticModels = emptyList(),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(),
        )
        val results = validator.validate(manifest)
        assertEquals(1, results.errors.size)
        assertTrue(results.errors.single().message.contains("kaboom"))
    }

    @Test
    fun `validator rejects empty rule list`() {
        assertFailsWith<IllegalArgumentException> {
            SemanticManifestValidator(emptyList())
        }
    }

    @Test
    fun `default rules list contains 28 rules`() {
        assertEquals(28, DefaultValidationRules.size)
    }

    @Test
    fun `result merge respects severity buckets`() {
        val w = ValidationWarning("warn")
        val e = ValidationError("err")
        val merged = SemanticManifestValidationResults.merge(
            listOf(
                SemanticManifestValidationResults(warnings = listOf(w)),
                SemanticManifestValidationResults(errors = listOf(e)),
            ),
        )
        assertEquals(listOf(w), merged.warnings)
        assertEquals(listOf(e), merged.errors)
        assertEquals(listOf<ValidationIssue>(e, w), merged.allIssues)
        assertTrue(merged.hasBlockingIssues)
    }
}
