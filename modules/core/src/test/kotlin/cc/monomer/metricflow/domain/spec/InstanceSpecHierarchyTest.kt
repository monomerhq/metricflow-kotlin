package cc.monomer.metricflow.domain.spec

import cc.monomer.metricflow.common.time.ExpandedTimeGranularity
import cc.monomer.metricflow.domain.manifest.model.enums.DatePart
import cc.monomer.metricflow.domain.manifest.model.enums.TimeGranularity
import cc.monomer.metricflow.domain.manifest.model.naming.METRIC_TIME_ELEMENT_NAME
import cc.monomer.metricflow.domain.manifest.model.references.EntityReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceSpecHierarchyTest {

    @Test
    fun `DimensionSpec dunder name reflects entity links`() {
        val spec = DimensionSpec(elementName = "country", entityLinks = listOf(EntityReference("listing")), alias = null)
        assertEquals("listing__country", spec.dunderName)
    }

    @Test
    fun `DimensionSpec withoutFirstEntityLink drops first link`() {
        val spec = DimensionSpec(
            elementName = "country",
            entityLinks = listOf(EntityReference("a"), EntityReference("b")),
            alias = null,
        )
        assertEquals(listOf(EntityReference("b")), spec.withoutFirstEntityLink().entityLinks)
    }

    @Test
    fun `DimensionSpec withoutFirstEntityLink fails when empty`() {
        val spec = DimensionSpec.fromElementName("country")
        assertFailsWith<IllegalStateException> { spec.withoutFirstEntityLink() }
    }

    @Test
    fun `EntitySpec asLinklessPrefix prepends self-as-entity`() {
        val spec = EntitySpec(elementName = "user", entityLinks = listOf(EntityReference("listing")), alias = null)
        assertEquals(listOf(EntityReference("user"), EntityReference("listing")), spec.asLinklessPrefix)
    }

    @Test
    fun `TimeDimensionSpec requires exactly one of grain or date_part`() {
        assertFailsWith<IllegalArgumentException> {
            TimeDimensionSpec(
                elementName = "ds",
                entityLinks = emptyList(),
                timeGranularity = null,
                datePart = null,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TimeDimensionSpec(
                elementName = "ds",
                entityLinks = emptyList(),
                timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
                datePart = DatePart.YEAR,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            )
        }
    }

    @Test
    fun `TimeDimensionSpec is_metric_time detection`() {
        val mt = TimeDimensionSpec(
            elementName = METRIC_TIME_ELEMENT_NAME,
            entityLinks = emptyList(),
            timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
            datePart = null,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        assertTrue(mt.isMetricTime)
        val other = mt.copy(elementName = "ds")
        assertFalse(other.isMetricTime)
    }

    @Test
    fun `TimeDimensionSpec generatePossibleSpecs covers all granularities and date parts`() {
        val specs = TimeDimensionSpec.generatePossibleSpecsForTimeDimension(
            timeDimensionReference = cc.monomer.metricflow.domain.manifest.model.references.TimeDimensionReference("ds"),
            entityLinks = emptyList(),
            customGranularities = mapOf(
                "fiscal" to ExpandedTimeGranularity("fiscal", TimeGranularity.MONTH),
            ),
        )
        // 8 standard grain entries (TimeGranularity has 8 entries: NANOSECOND..YEAR per source) + 1 custom + DatePart.entries.size
        assertEquals(TimeGranularity.entries.size + 1 + DatePart.entries.size, specs.size)
    }

    @Test
    fun `GroupByMetricSpec post-init enforces matching last entity link`() {
        val ok = GroupByMetricSpec(
            elementName = "bookings",
            entityLinks = listOf(EntityReference("listing")),
            metricSubqueryEntityLinks = listOf(EntityReference("listing")),
            alias = null,
        )
        assertEquals("bookings", ok.elementName)
        assertFailsWith<IllegalStateException> {
            GroupByMetricSpec(
                elementName = "bookings",
                entityLinks = listOf(EntityReference("listing")),
                metricSubqueryEntityLinks = listOf(EntityReference("user")),
                alias = null,
            )
        }
    }

    @Test
    fun `GroupByMetricSpec equality ignores subquery links and alias`() {
        val a = GroupByMetricSpec(
            elementName = "bookings",
            entityLinks = listOf(EntityReference("listing")),
            metricSubqueryEntityLinks = listOf(EntityReference("listing")),
            alias = null,
        )
        val b = GroupByMetricSpec(
            elementName = "bookings",
            entityLinks = listOf(EntityReference("listing")),
            // GroupByMetricSpec invariant: last subquery link must equal last entity link if entity_links non-empty.
            metricSubqueryEntityLinks = listOf(EntityReference("user"), EntityReference("listing")),
            alias = "bk_count",
        )
        assertEquals(a, b)
    }

    @Test
    fun `MetricSpec withAlias preserves other fields`() {
        val a = MetricSpec.fromElementName("bookings")
        val b = a.withAlias("bookings_alias")
        assertEquals("bookings_alias", b.alias)
        assertEquals(a.elementName, b.elementName)
        assertEquals(a.whereFilterSpecs, b.whereFilterSpecs)
    }

    @Test
    fun `MetadataSpec dunder name appends agg_type`() {
        val m = MetadataSpec("row_count", aggType = cc.monomer.metricflow.domain.manifest.model.enums.AggregationType.SUM)
        assertEquals("row_count__sum", m.dunderName)
        assertEquals("just_a_name", MetadataSpec("just_a_name", aggType = null).dunderName)
    }

    @Test
    fun `visitor dispatch is exhaustive for all variants`() {
        val visitor = object : InstanceSpecVisitor<String> {
            override fun visitSimpleMetricInputSpec(spec: SimpleMetricInputSpec) = "smi"
            override fun visitDimensionSpec(spec: DimensionSpec) = "dim"
            override fun visitTimeDimensionSpec(spec: TimeDimensionSpec) = "tdim"
            override fun visitEntitySpec(spec: EntitySpec) = "ent"
            override fun visitGroupByMetricSpec(spec: GroupByMetricSpec) = "gbm"
            override fun visitMetricSpec(spec: MetricSpec) = "met"
            override fun visitMetadataSpec(spec: MetadataSpec) = "mta"
        }
        val results = listOf<InstanceSpec>(
            SimpleMetricInputSpec("a", null),
            DimensionSpec.fromElementName("b"),
            TimeDimensionSpec(
                elementName = "ds",
                entityLinks = emptyList(),
                timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY),
                datePart = null,
                aggregationState = null,
                windowFunctions = emptyList(),
                alias = null,
            ),
            EntitySpec.fromElementName("e"),
            GroupByMetricSpec(
                elementName = "m",
                entityLinks = emptyList(),
                metricSubqueryEntityLinks = listOf(EntityReference("e")),
                alias = null,
            ),
            MetricSpec.fromElementName("bookings"),
            MetadataSpec("meta", aggType = null),
        ).map { it.accept(visitor) }
        assertEquals(listOf("smi", "dim", "tdim", "ent", "gbm", "met", "mta"), results)
    }

    @Test
    fun `LinkableInstanceSpec mergeLinkableSpecs concatenates`() {
        val a = listOf<LinkableInstanceSpec>(DimensionSpec.fromElementName("a"))
        val b = listOf<LinkableInstanceSpec>(EntitySpec.fromElementName("b"))
        val merged = LinkableInstanceSpec.mergeLinkableSpecs(a, b)
        assertEquals(2, merged.size)
        assertEquals(DimensionSpec.fromElementName("a"), merged[0])
    }

    @Test
    fun `TimeDimensionSpec base granularity sort key falls back to high value`() {
        val datePartOnly = TimeDimensionSpec(
            elementName = "ds",
            entityLinks = emptyList(),
            timeGranularity = null,
            datePart = DatePart.YEAR,
            aggregationState = null,
            windowFunctions = emptyList(),
            alias = null,
        )
        assertNull(datePartOnly.timeGranularity)
        assertEquals(100, datePartOnly.baseGranularitySortKey)
        val day = datePartOnly.copy(timeGranularity = ExpandedTimeGranularity.fromTimeGranularity(TimeGranularity.DAY), datePart = null)
        assertNotNull(day.baseGranularity)
        assertEquals(TimeGranularity.DAY.toInt(), day.baseGranularitySortKey)
    }
}
