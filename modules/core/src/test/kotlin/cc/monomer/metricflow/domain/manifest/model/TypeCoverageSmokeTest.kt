package cc.monomer.metricflow.domain.manifest.model

import cc.monomer.metricflow.domain.manifest.model.element.Dimension
import cc.monomer.metricflow.domain.manifest.model.element.DimensionTypeParams
import cc.monomer.metricflow.domain.manifest.model.element.Entity
import cc.monomer.metricflow.domain.manifest.model.element.Measure
import cc.monomer.metricflow.domain.manifest.model.enums.AggregationType
import cc.monomer.metricflow.domain.manifest.model.enums.DimensionType
import cc.monomer.metricflow.domain.manifest.model.enums.EntityType
import cc.monomer.metricflow.domain.manifest.model.enums.MetricType
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.references.DimensionReference
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import cc.monomer.metricflow.domain.manifest.model.references.MeasureReference
import cc.monomer.metricflow.domain.manifest.model.references.MetricReference
import cc.monomer.metricflow.domain.manifest.model.references.SemanticModelReference
import cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference
import cc.monomer.metricflow.domain.manifest.model.serialization.ManifestJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Programmatic-construction smoke tests for the most-used types.
 *
 * Sanity-checks that:
 * - References (value classes) serialise as bare strings.
 * - Enums serialise as the Python-canonical lower-case value.
 * - Top-level types accept the documented constructor arguments and serialise back to a
 *   structurally sensible JSON object.
 */
class TypeCoverageSmokeTest {

    private val plainJson = Json { ignoreUnknownKeys = false; prettyPrint = false }

    @Test
    fun `MetricReference round-trips as a bare string`() {
        val ref = MetricReference("bookings")
        val encoded = plainJson.encodeToString(MetricReference.serializer(), ref)
        assertEquals("\"bookings\"", encoded)
        val decoded = plainJson.decodeFromString(MetricReference.serializer(), encoded)
        assertEquals(ref, decoded)
    }

    @Test
    fun `MeasureReference dimension entity round-trip as bare strings`() {
        val m = MeasureReference("x"); val d = DimensionReference("y")
        val e = EntityReference("z"); val td = TimeDimensionReference("ds")
        assertEquals("\"x\"", plainJson.encodeToString(MeasureReference.serializer(), m))
        assertEquals("\"y\"", plainJson.encodeToString(DimensionReference.serializer(), d))
        assertEquals("\"z\"", plainJson.encodeToString(EntityReference.serializer(), e))
        assertEquals("\"ds\"", plainJson.encodeToString(TimeDimensionReference.serializer(), td))
    }

    @Test
    fun `SemanticModelReference is keyed by semantic_model_name`() {
        val ref = SemanticModelReference("my_model")
        // value class serialises as its single field's value
        assertEquals("\"my_model\"", plainJson.encodeToString(SemanticModelReference.serializer(), ref))
    }

    @Test
    fun `MetricType serialises as lowercase value`() {
        assertEquals("\"simple\"", plainJson.encodeToString(MetricType.serializer(), MetricType.SIMPLE))
        assertEquals("\"conversion\"", plainJson.encodeToString(MetricType.serializer(), MetricType.CONVERSION))
        // Decode also works
        val decoded = plainJson.decodeFromString(MetricType.serializer(), "\"derived\"")
        assertEquals(MetricType.DERIVED, decoded)
    }

    @Test
    fun `TimeGranularity ordering reflects the Python toInt rule`() {
        assertTrue(TimeGranularity.DAY.isSmallerThan(TimeGranularity.WEEK))
        assertTrue(TimeGranularity.WEEK.isSmallerThan(TimeGranularity.MONTH))
        assertTrue(TimeGranularity.MONTH.isSmallerThanOrEqual(TimeGranularity.MONTH))
        assertEquals(TimeGranularity.DAY, TimeGranularity.fromString("day"))
    }

    @Test
    fun `Dimension shape and computed properties`() {
        val timeDim = Dimension(
            name = "ds",
            type = DimensionType.TIME,
            typeParams = DimensionTypeParams(timeGranularity = TimeGranularity.DAY),
        )
        assertEquals(DimensionReference("ds"), timeDim.reference)
        assertEquals(TimeDimensionReference("ds"), timeDim.timeDimensionReference)
        assertNull(timeDim.validityParams)

        val catDim = Dimension(name = "country", type = DimensionType.CATEGORICAL)
        assertNull(catDim.timeDimensionReference)
        assertEquals(DimensionReference("country"), catDim.reference)
    }

    @Test
    fun `Entity isLinkableEntityType`() {
        assertTrue(Entity(name = "u", type = EntityType.PRIMARY).isLinkableEntityType)
        assertTrue(Entity(name = "u", type = EntityType.UNIQUE).isLinkableEntityType)
        assertTrue(Entity(name = "u", type = EntityType.NATURAL).isLinkableEntityType)
        assertEquals(false, Entity(name = "u", type = EntityType.FOREIGN).isLinkableEntityType)
    }

    @Test
    fun `Measure exposes a MeasureReference`() {
        val m = Measure(name = "bookings", agg = AggregationType.SUM)
        assertEquals(MeasureReference("bookings"), m.reference)
    }

    @Test
    fun `Metric SIMPLE inputMetrics is empty`() {
        // Python populates input_measures via a transformation pass; in a raw constructed
        // Metric it stays the empty default. inputMetrics returns empty for SIMPLE regardless.
        val inputMeasure = MetricInputMeasure(name = "bookings")
        val metric = Metric(
            name = "bookings",
            type = MetricType.SIMPLE,
            typeParams = MetricTypeParams(
                measure = inputMeasure,
                inputMeasures = listOf(inputMeasure),
            ),
        )
        assertEquals(emptyList(), metric.inputMetrics)
        assertEquals(listOf(MeasureReference("bookings")), metric.measureReferences)
    }

    @Test
    fun `SemanticManifest serialises with snake_case keys`() {
        val m = SemanticManifest(
            semanticModels = emptyList(),
            metrics = emptyList(),
            projectConfiguration = ProjectConfiguration(),
        )
        val s = ManifestJson.encodeToString(SemanticManifest.serializer(), m)
        val tree = Json.parseToJsonElement(s).jsonObject
        assertTrue("semantic_models" in tree.keys, "expected snake_case key 'semantic_models'")
        assertTrue("project_configuration" in tree.keys)
        assertTrue("saved_queries" in tree.keys)
        // Project config nested key — sanity
        assertEquals(
            "0",
            tree["project_configuration"]!!.jsonObject["dsi_package_version"]!!.jsonObject["major_version"]!!.jsonPrimitive.content,
        )
    }
}
